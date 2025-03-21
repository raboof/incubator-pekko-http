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

package docs.http.javadsl.server;

import org.apache.pekko.http.javadsl.coding.Coder;
import org.apache.pekko.http.javadsl.model.ContentTypes;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AuthorizationFailedRejection;
import org.apache.pekko.http.javadsl.server.MethodRejection;
import org.apache.pekko.http.javadsl.server.MissingCookieRejection;
import org.apache.pekko.http.javadsl.server.RejectionHandler;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.server.ValidationRejection;
import org.apache.pekko.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.stream.Collectors;

//#example1
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.decodeRequestWith;
import static org.apache.pekko.http.javadsl.server.Directives.get;
import static org.apache.pekko.http.javadsl.server.Directives.path;
import static org.apache.pekko.http.javadsl.server.Directives.post;
//#example1
//#custom-handler-example-java
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.path;
import static org.apache.pekko.http.javadsl.server.Directives.handleRejections;

//#custom-handler-example-java
//#example-json
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.path;
import static org.apache.pekko.http.javadsl.server.Directives.handleRejections;
import static org.apache.pekko.http.javadsl.server.Directives.validate;

//#example-json
public class RejectionHandlerExamplesTest extends JUnitRouteTest {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  void example1() {
    //#example1
    final Route route = path("order", () ->
      concat(
        get(() ->
          complete("Received GET")
        ),
        post(() ->
          decodeRequestWith(Coder.Gzip, () ->
            complete("Received compressed POST")
          )
        )
      ));
    //#example1
  }

  void customRejectionHandler() {
    //#custom-handler-example-java
    final RejectionHandler rejectionHandler = RejectionHandler.newBuilder()
      .handle(MissingCookieRejection.class, rej ->
        complete(StatusCodes.BAD_REQUEST, "No cookies, no service!!!")
      )
      .handle(AuthorizationFailedRejection.class, rej ->
        complete(StatusCodes.FORBIDDEN, "You're out of your depth!")
      )
      .handle(ValidationRejection.class, rej ->
        complete(StatusCodes.INTERNAL_SERVER_ERROR, "That wasn't valid! " + rej.message())
      )
      .handleAll(MethodRejection.class, rejections -> {
        String supported = rejections.stream()
          .map(rej -> rej.supported().name())
          .collect(Collectors.joining(" or "));
        return complete(StatusCodes.METHOD_NOT_ALLOWED, "Can't do that! Supported: " + supported + "!");
      })
      .handleNotFound(complete(StatusCodes.NOT_FOUND, "Not here!"))
      .build();

    // Route that will be bound to the Http
    final Route wrapped = handleRejections(rejectionHandler,
      this::getRoute); // Some route structure for this Server
    //#custom-handler-example-java
  }

  Route getRoute() {
    return null;
  }

  @Test
  public void customRejectionResponse() {
    //#example-json
    final RejectionHandler rejectionHandler = RejectionHandler.defaultHandler()
      .mapRejectionResponse(response -> {
        if (response.entity() instanceof HttpEntity.Strict) {
          // since all Akka default rejection responses are Strict this will handle all rejections
          String message = ((HttpEntity.Strict) response.entity()).getData().utf8String()
            .replaceAll("\"", "\\\"");
          // we create a new copy the response in order to keep all headers and status code,
          // replacing the original entity with a custom message as hand rolled JSON you could the
          // entity using your favourite marshalling library (e.g. spray json or anything else)
          return response.withEntity(ContentTypes.APPLICATION_JSON,
            "{\"rejection\": \"" + message + "\"}");
        } else {
          // pass through all other types of responses
          return response;
        }
      });

    Route route = handleRejections(rejectionHandler, () ->
      path("hello", () ->
        complete("Hello there")
      ));

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/nope"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"The requested resource could not be found.\"}");

    Route anotherOne = handleRejections(rejectionHandler, () ->
      validate(() -> false, "Whoops, bad request!", () ->
        complete("Hello there")
    ));

    // tests:
    testRoute(anotherOne)
      .run(HttpRequest.GET("/hello"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"Whoops, bad request!\"}");
    //#example-json
  }

}