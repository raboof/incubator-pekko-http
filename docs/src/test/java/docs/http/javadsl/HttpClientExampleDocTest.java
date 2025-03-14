/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.*;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.apache.pekko.http.javadsl.model.headers.SetCookie;
import org.apache.pekko.util.ByteString;
import scala.concurrent.ExecutionContextExecutor;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.http.javadsl.ClientTransport;
import org.apache.pekko.http.javadsl.settings.ClientConnectionSettings;
import org.apache.pekko.http.javadsl.settings.ConnectionPoolSettings;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.OutgoingConnection;

import static org.apache.pekko.http.javadsl.ConnectHttp.toHost;
import static org.apache.pekko.util.ByteString.emptyByteString;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

//#manual-entity-consume-example-1
import java.io.File;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.pekko.stream.javadsl.Framing;
import org.apache.pekko.http.javadsl.model.*;
import scala.concurrent.duration.FiniteDuration;
//#manual-entity-consume-example-1

//#single-request-in-actor-example
import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import static org.apache.pekko.pattern.PatternsCS.pipe;

//#single-request-in-actor-example

@SuppressWarnings("unused")
public class HttpClientExampleDocTest {

  static HttpResponse responseFromSomewhere() {
    return HttpResponse.create();
  }

  void manualEntityComsumeExample() {
    //#manual-entity-consume-example-1

    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();

    final HttpResponse response = responseFromSomewhere();

    final Function<ByteString, ByteString> transformEachLine = line -> line /* some transformation here */;

    final int maximumFrameLength = 256;

    response.entity().getDataBytes()
      .via(Framing.delimiter(ByteString.fromString("\n"), maximumFrameLength, FramingTruncation.ALLOW))
      .map(transformEachLine::apply)
      .runWith(FileIO.toPath(new File("/tmp/example.out").toPath()), system);
    //#manual-entity-consume-example-1
    system.terminate();
  }

  private static class ConsumeExample2 {
    //#manual-entity-consume-example-2
    final class ExamplePerson {
      final String name;
      public ExamplePerson(String name) { this.name = name; }
    }

    public ExamplePerson parse(ByteString line) {
      return new ExamplePerson(line.utf8String());
    }

    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();

    final HttpResponse response = responseFromSomewhere();

    // toStrict to enforce all data be loaded into memory from the connection
    final CompletionStage<HttpEntity.Strict> strictEntity = response.entity()
        .toStrict(FiniteDuration.create(3, TimeUnit.SECONDS).toMillis(), system);

    // You can now use `getData` to get the data directly...
    final CompletionStage<ExamplePerson> person1 =
      strictEntity.thenApply(strict -> parse(strict.getData()));

    // Though it is also still possible to use the streaming API to consume dataBytes,
    // even though now they're in memory:
    final CompletionStage<ExamplePerson> person2 =
      strictEntity
        .thenCompose(strict ->
          strict.getDataBytes()
            .runFold(emptyByteString(), (acc, b) -> acc.concat(b), system)
            .thenApply(this::parse)
        );
    //#manual-entity-consume-example-2
  }

  private static class ConsumeExample3 {
    //#manual-entity-consume-example-3
    final class ExamplePerson {
      final String name;
      public ExamplePerson(String name) { this.name = name; }
    }

    public ExamplePerson parse(ByteString line) {
      return new ExamplePerson(line.utf8String());
    }

    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();

    // run a single request, consuming it completely in a single stream
    public CompletionStage<ExamplePerson> runRequest(HttpRequest request) {
      return Http.get(system)
        .singleRequest(request)
        .thenCompose(response ->
          response.entity().getDataBytes()
            .runReduce((a, b) -> a.concat(b), system)
            .thenApply(this::parse)
        );
    }

    final List<HttpRequest> requests = new ArrayList<>();

    final Flow<ExamplePerson, Integer, NotUsed> exampleProcessingFlow = Flow
            .fromFunction(person -> person.toString().length());

    final CompletionStage<Done> stream = Source
            .from(requests)
            .mapAsync(1, this::runRequest)
            .via(exampleProcessingFlow)
            .runWith(Sink.ignore(), system);

    //#manual-entity-consume-example-3
  }

  void manualEntityDiscardExample1() {
    //#manual-entity-discard-example-1
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();

    final HttpResponse response = responseFromSomewhere();

    final HttpMessage.DiscardedEntity discarded = response.discardEntityBytes(system);

    discarded.completionStage().whenComplete((done, ex) -> {
      System.out.println("Entity discarded completely!");
    });
    //#manual-entity-discard-example-1
    system.terminate();
  }

  void manualEntityDiscardExample2() {
    //#manual-entity-discard-example-2
    final ActorSystem system = ActorSystem.create();
    final ExecutionContextExecutor dispatcher = system.dispatcher();

    final HttpResponse response = responseFromSomewhere();

    final CompletionStage<Done> discardingComplete = response.entity().getDataBytes().runWith(Sink.ignore(), system);

    discardingComplete.whenComplete((done, ex) -> {
      System.out.println("Entity discarded completely!");
    });
    //#manual-entity-discard-example-2
    system.terminate();
  }


  // compile only test
  public void testConstructRequest() {
    //#outgoing-connection-example

    final ActorSystem system = ActorSystem.create();

    final Flow<HttpRequest, HttpResponse, CompletionStage<OutgoingConnection>> connectionFlow =
            Http.get(system).connectionTo("pekko.apache.org").http();
    final CompletionStage<HttpResponse> responseFuture =
            // This is actually a bad idea in general. Even if the `connectionFlow` was instantiated only once above,
            // a new connection is opened every single time, `runWith` is called. Materialization (the `runWith` call)
            // and opening up a new connection is slow.
            //
            // The `outgoingConnection` API is very low-level. Use it only if you already have a `Source[HttpRequest, _]`
            // (other than Source.single) available that you want to use to run requests on a single persistent HTTP
            // connection.
            //
            // Unfortunately, this case is so uncommon, that we couldn't come up with a good example.
            //
            // In almost all cases it is better to use the `Http().singleRequest()` API instead.
            Source.single(HttpRequest.create("/"))
                    .via(connectionFlow)
                    .runWith(Sink.<HttpResponse>head(), system);
    //#outgoing-connection-example
    system.terminate();
  }

  // compile only test
  public void testSingleRequestExample() {
    //#single-request-example
    final ActorSystem system = ActorSystem.create();

    final CompletionStage<HttpResponse> responseFuture =
      Http.get(system)
          .singleRequest(HttpRequest.create("http://pekko.apache.org"));
    //#single-request-example
    system.terminate();
  }

  // compile only test
  public void singleRequestInActorExample1() {
    //#single-request-in-actor-example
    class SingleRequestInActorExample extends AbstractActor {
      final Http http = Http.get(context().system());
      final ExecutionContextExecutor dispatcher = context().dispatcher();

      @Override
      public Receive createReceive() {
        return receiveBuilder()
          .match(String.class, url -> pipe(fetch(url), dispatcher).to(self()))
          .build();
      }

      CompletionStage<HttpResponse> fetch(String url) {
        return http.singleRequest(HttpRequest.create(url));
      }
    }
    //#single-request-in-actor-example
  }

  // compile only test
  public void testSingleRequestWithHttpsProxyExample() {
    //#https-proxy-example-single-request

    final ActorSystem system = ActorSystem.create();

    ClientTransport proxy = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved("192.168.2.5", 8080));
    ConnectionPoolSettings poolSettingsWithHttpsProxy = ConnectionPoolSettings.create(system)
        .withConnectionSettings(ClientConnectionSettings.create(system).withTransport(proxy));

    final CompletionStage<HttpResponse> responseFuture =
        Http.get(system)
            .singleRequest(
                  HttpRequest.create("https://github.com"),
                  Http.get(system).defaultClientHttpsContext(),
                  poolSettingsWithHttpsProxy, // <- pass in the custom settings here
                  system.log());

    //#https-proxy-example-single-request
    system.terminate();
  }

  // compile only test
  public void testSingleRequestWithHttpsProxyExampleWithAuth() {

    final ActorSystem system = ActorSystem.create();

    //#auth-https-proxy-example-single-request
    InetSocketAddress proxyAddress =
      InetSocketAddress.createUnresolved("192.168.2.5", 8080);
    HttpCredentials credentials =
      HttpCredentials.createBasicHttpCredentials("proxy-user", "secret-proxy-pass-dont-tell-anyone");

    ClientTransport proxy = ClientTransport.httpsProxy(proxyAddress, credentials); // include credentials
    ConnectionPoolSettings poolSettingsWithHttpsProxy = ConnectionPoolSettings.create(system)
        .withConnectionSettings(ClientConnectionSettings.create(system).withTransport(proxy));

    final CompletionStage<HttpResponse> responseFuture =
        Http.get(system)
            .singleRequest(
                  HttpRequest.create("https://github.com"),
                  Http.get(system).defaultClientHttpsContext(),
                  poolSettingsWithHttpsProxy, // <- pass in the custom settings here
                  system.log());

    //#auth-https-proxy-example-single-request
    system.terminate();
  }

  // compile only test
  public void testCollectingHeadersExample() {

    final ActorSystem system = ActorSystem.create();

    //#collecting-headers-example
    final HttpResponse response = responseFromSomewhere();

    final Iterable<SetCookie> setCookies = response.getHeaders(SetCookie.class);

    System.out.println("Cookies set by a server: " + setCookies);
    response.discardEntityBytes(system);
    //#collecting-headers-example
    system.terminate();
  }

}
