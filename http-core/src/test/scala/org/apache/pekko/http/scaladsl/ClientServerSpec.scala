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

package org.apache.pekko.http.scaladsl

import java.io.{ BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter }
import java.net.{ BindException, Socket }
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.{ Success, Try }
import com.typesafe.sslconfig.pekko.PekkoSSLConfig
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import com.typesafe.sslconfig.ssl.SSLLooseConfig
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.event.Logging
import pekko.event.Logging.LogEvent
import pekko.http.impl.engine.HttpIdleTimeoutException
import pekko.http.impl.engine.ws.ByteStringSinkProbe
import pekko.http.impl.util.ExampleHttpContexts.loadX509Certificate
import pekko.http.impl.util._
import pekko.http.scaladsl.Http.ServerBinding
import pekko.http.scaladsl.model.HttpEntity._
import pekko.http.scaladsl.model.HttpMethods._
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.{ `User-Agent`, Accept, Age, Date, Host, Server }
import pekko.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import pekko.io.Tcp.SO
import pekko.stream.scaladsl._
import pekko.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import pekko.stream.testkit._
import pekko.stream._
import pekko.testkit._
import pekko.util.ByteString
import scala.annotation.nowarn
import com.typesafe.config.{ Config, ConfigFactory }

import javax.net.ssl.{ SSLContext, TrustManagerFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.Eventually.eventually

class ClientServerSpec extends ClientServerSpecBase(http2 = false)
// Runs the same tests but with http2 enabled which makes sure the alternative server pipeline from Http2.scala
// is tested as well.
class ClientServerHttp2EnabledSpec extends ClientServerSpecBase(http2 = true)

abstract class ClientServerSpecBase(http2: Boolean) extends PekkoSpecWithMaterializer(
      s"""
     pekko.http.server.preview.enable-http2 = $http2
     pekko.http.server.request-timeout = infinite
     pekko.http.server.log-unencrypted-network-bytes = 200
     pekko.http.client.log-unencrypted-network-bytes = 200
  """) with ScalaFutures {
  import system.dispatcher

  val testConf2: Config =
    ConfigFactory.parseString("pekko.stream.materializer.subscription-timeout.timeout = 1 s")
      .withFallback(system.settings.config)
  val system2 = ActorSystem(getClass.getSimpleName, testConf2)
  val materializer2 = SystemMaterializer(system2).materializer

  "The low-level HTTP infrastructure" should {

    "properly bind a server" in {
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      val binding = Http().newServerAt("127.0.0.1", 0).connectionSource().to(Sink.fromSubscriber(probe)).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      Await.result(binding, 1.second.dilated).unbind()
      sub.cancel()
    }

    "properly bind a server with a default port set via settings" in {
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      // not really testing anything here, problem is that it is hard to find an unused port otherwise
      val settings = ServerSettings(system).withDefaultHttpPort(0)
      val bindingF =
        Http().newServerAt("0.0.0.0", 0).withSettings(settings).connectionSource().to(Sink.fromSubscriber(probe)).run()
      val sub = probe.expectSubscription() // if we get it we are bound
      val binding = Await.result(bindingF, 1.second.dilated)
      // though, that wouldn't probably happen because binding ports < 1024 is restricted in most environments
      binding.localAddress.getPort should not be 80
      binding.unbind()
      sub.cancel()
    }

    "report failure if bind fails" in EventFilter[BindException](occurrences = 2).intercept {
      val port = 1025 // non-root users typically can't bind to port numbers below 1024
      val binding = Http().newServerAt("localhost", port).connectionSource()
      val probe1 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      // Bind succeeded, we have a local address
      val b1 = Await.result(binding.to(Sink.fromSubscriber(probe1)).run(), 3.seconds.dilated)
      probe1.expectSubscription()

      val probe2 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy {
        Await.result(binding.to(Sink.fromSubscriber(probe2)).run(), 3.seconds.dilated)
      }
      probe2.expectSubscriptionAndError()

      val probe3 = TestSubscriber.manualProbe[Http.IncomingConnection]()
      an[BindFailedException] shouldBe thrownBy {
        Await.result(binding.to(Sink.fromSubscriber(probe3)).run(), 3.seconds.dilated)
      }
      probe3.expectSubscriptionAndError()

      // Now unbind the first
      Await.result(b1.unbind(), 1.second.dilated)
      probe1.expectComplete()

      if (!pekko.util.Helpers.isWindows) {
        val probe4 = TestSubscriber.manualProbe[Http.IncomingConnection]()
        // Bind succeeded, we have a local address
        val b2 = Await.result(binding.to(Sink.fromSubscriber(probe4)).run(), 3.seconds.dilated)
        probe4.expectSubscription()

        // clean up
        Await.result(b2.unbind(), 1.second.dilated)
      }
    }

    "properly terminate client when server is not running" in Utils.assertAllStagesStopped {
      for (i <- 1 to 10)
        withClue(s"iterator $i: ") {
          Source.single(HttpRequest(HttpMethods.POST, "/test", List.empty,
            HttpEntity(MediaTypes.`text/plain`.withCharset(HttpCharsets.`UTF-8`), "buh")))
            .via(Http(actorSystem).connectionTo("localhost").toPort(7777).http())
            .runWith(Sink.head)
            .failed
            .futureValue shouldBe a[StreamTcpException]
        }
    }

    "run with bindSync" in {
      val binding = Http().newServerAt("localhost", 0).bindSync(_ => HttpResponse())
      val b1 = Await.result(binding, 3.seconds.dilated)

      val (_, f) = Http().connectionTo("localhost").toPort(b1.localAddress.getPort).http()
        .runWith(Source.single(HttpRequest(uri = "/abc")), Sink.head)

      Await.result(f, 1.second.dilated)
      Await.result(b1.unbind(), 1.second.dilated)
    }

    "prevent more than the configured number of max-connections with bind" in {
      val settings = ServerSettings(system).withMaxConnections(1)

      val receivedSlow = Promise[Long]()
      val receivedFast = Promise[Long]()

      def handle(req: HttpRequest): Future[HttpResponse] = {
        req.uri.path.toString match {
          case "/slow" =>
            receivedSlow.complete(Success(System.nanoTime()))
            pekko.pattern.after(1.seconds.dilated, system.scheduler)(Future.successful(HttpResponse()))
          case "/fast" =>
            receivedFast.complete(Success(System.nanoTime()))
            Future.successful(HttpResponse())
        }
      }

      val binding = Http().newServerAt("localhost", 0).withSettings(settings).bind(handle)
      val b1 = Await.result(binding, 3.seconds.dilated)

      def runRequest(uri: Uri): Unit =
        Http().connectionTo("localhost").toPort(b1.localAddress.getPort).http()
          .runWith(Source.single(HttpRequest(uri = uri)), Sink.head)

      runRequest("/slow")

      // wait until first request was received (but not yet answered)
      val slowTime = Await.result(receivedSlow.future, 2.second.dilated)

      // should be blocked by the slow connection still being open
      runRequest("/fast")

      val fastTime = Await.result(receivedFast.future, 2.second.dilated)
      val diff = fastTime - slowTime
      diff should be > 1000000000L // the diff must be at least the time to complete the first request and to close the first connection

      Await.result(b1.unbind(), 1.second.dilated)
    }

    // The Remote-Address header is deprecated, but we still want to test it works
    "Remote-Address header" should {
      def handler(req: HttpRequest): HttpResponse = {
        @nowarn("msg=deprecated")
        val entity = req.header[headers.`Remote-Address`].flatMap(_.address.toIP).flatMap(_.port).toString
        HttpResponse(entity = entity)
      }

      "be added when using bind API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().newServerAt("localhost", 0).withSettings(settings).connectionSource()
            .map(_.flow.join(Flow[HttpRequest].map(handler)).run())
            .to(Sink.ignore)
            .run()
      }

      "be added when using bindFlow API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().newServerAt("localhost", 0).withSettings(settings).bindFlow(Flow[HttpRequest].map(handler))
      }

      "be added when using bindSync API" in new RemoteAddressTestScenario {
        def createBinding(): Future[ServerBinding] =
          Http().newServerAt("localhost", 0).withSettings(settings).bindSync(handler)
      }

      abstract class RemoteAddressTestScenario {
        val settings = ServerSettings(system).withRemoteAddressHeader(true)
        def createBinding(): Future[ServerBinding]

        val binding = createBinding()
        val b1 = Await.result(binding, 3.seconds.dilated)

        val (conn, response) =
          Source.single(HttpRequest(uri = "/abc"))
            .viaMat(Http().outgoingConnection("localhost", b1.localAddress.getPort))(Keep.right)
            .toMat(Sink.head)(Keep.both)
            .run()

        val r = Await.result(response, 1.second.dilated)
        val c = Await.result(conn, 1.second.dilated)
        Await.result(b1.unbind(), 1.second.dilated)

        toStrict(r.entity).data.utf8String shouldBe s"Some(${c.localAddress.getPort})"
      }
    }

    "timeouts" should {
      def bindServer(hostname: String, port: Int, serverIdleTimeout: FiniteDuration): (Promise[Long], ServerBinding) = {
        val s = ServerSettings(system)
        val settings = s.withTimeouts(s.timeouts.withIdleTimeout(serverIdleTimeout))

        val receivedRequest = Promise[Long]()

        def handle(req: HttpRequest): Future[HttpResponse] = {
          receivedRequest.complete(Success(System.nanoTime()))
          Promise().future // never complete the request with a response; we're waiting for the timeout to happen, nothing else
        }

        val binding = Http().newServerAt(hostname, port).withSettings(settings).bind(handle)
        val b1 = Await.result(binding, 3.seconds.dilated)
        (receivedRequest, b1)
      }

      "support server timeouts" should {
        "close connection with idle client after idleTimeout" in {
          val serverIdleTimeout = 300.millis
          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer("localhost", 0, serverIdleTimeout)

          try {
            def runIdleRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverEnds = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().outgoingConnection("localhost", b1.localAddress.getPort)
                .runWith(Source.single(HttpRequest(PUT, uri, entity = itNeverEnds)), Sink.head)
                ._2
            }

            val clientsResponseFuture = runIdleRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[StreamTcpException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }

            (System.nanoTime() - serverReceivedRequestAtNanos).millis should be >= serverIdleTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }
      }

      "support client timeouts" should {
        "close connection with idle server after idleTimeout (using connection level client API)" in {
          val serverIdleTimeout = 10.seconds.dilated

          val clientIdleTimeout = 345.millis.dilated
          val clientSettings = ClientConnectionSettings(system).withIdleTimeout(clientIdleTimeout)

          val (receivedRequest: Promise[Long], binding: ServerBinding) =
            bindServer("localhost", port = 0, serverIdleTimeout)

          try {
            def runRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().outgoingConnection(binding.localAddress.getHostName, binding.localAddress.getPort,
                settings = clientSettings)
                .runWith(Source.single(HttpRequest(POST, uri, entity = itNeverSends)), Sink.head)
                ._2
            }

            val clientSentRequestAtNanos = System.nanoTime()
            val clientsResponseFuture = runRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }
            val clientSawTimeoutAtNanos = System.nanoTime()
            (clientSawTimeoutAtNanos - clientSentRequestAtNanos).nanos should be >= clientIdleTimeout
            (clientSawTimeoutAtNanos - serverReceivedRequestAtNanos).nanos should be < serverIdleTimeout
          } finally Await.result(binding.unbind(), 1.second.dilated)
        }

        "close connection with idle server after idleTimeout (using pool level client API)" in {
          val serverTimeout = 10.seconds.dilated

          val cs = ConnectionPoolSettings(system)
          val clientTimeout = 345.millis.dilated
          val clientPoolSettings = cs.withIdleTimeout(clientTimeout)

          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer("localhost", 0, serverTimeout)

          try {
            val pool = Http().cachedHostConnectionPool[Int]("localhost", b1.localAddress.getPort, clientPoolSettings)

            def runRequest(uri: Uri): Future[(Try[HttpResponse], Int)] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Source.single(HttpRequest(POST, uri, entity = itNeverSends) -> 1)
                .via(pool)
                .runWith(Sink.head)
            }

            val clientsResponseFuture = runRequest("/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 2.second.dilated)
            }
            val actualTimeout = System.nanoTime() - serverReceivedRequestAtNanos
            actualTimeout.nanos should be >= clientTimeout
            actualTimeout.nanos should be < serverTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }

        "close connection with idle server after idleTimeout (using request level client API)" in {
          val serverTimeout = 10.seconds.dilated

          val cs = ConnectionPoolSettings(system)
          val clientTimeout = 345.millis.dilated
          val clientPoolSettings = cs.withIdleTimeout(clientTimeout)

          val (receivedRequest: Promise[Long], b1: ServerBinding) = bindServer("localhost", 0, serverTimeout)

          try {
            def runRequest(uri: Uri): Future[HttpResponse] = {
              val itNeverSends = Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source.maybe[ByteString])
              Http().singleRequest(HttpRequest(POST, uri, entity = itNeverSends), settings = clientPoolSettings)
            }

            val clientsResponseFuture = runRequest(s"http://localhost:${b1.localAddress.getPort}/")

            // await for the server to get the request
            val serverReceivedRequestAtNanos = Await.result(receivedRequest.future, 2.seconds.dilated)

            // waiting for the timeout to happen on the client
            intercept[TimeoutException] {
              Await.result(clientsResponseFuture, 3.second.dilated)
            }
            val actualTimeout = System.nanoTime() - serverReceivedRequestAtNanos
            actualTimeout.nanos should be >= clientTimeout
            actualTimeout.nanos should be < serverTimeout
          } finally Await.result(b1.unbind(), 1.second.dilated)
        }

        "avoid client vs. server race-condition for persistent connections with keep-alive-timeout" in Utils.assertAllStagesStopped {
          def handler(request: HttpRequest): Future[HttpResponse] = Future.successful(HttpResponse())
          val serverSettings = ServerSettings(system).mapTimeouts(_.withIdleTimeout(300.millis))
          val binding = Http().newServerAt("127.0.0.1", 0).withSettings(serverSettings).bind(handler).futureValue
          val uri = "http://" + binding.localAddress.getHostString + ":" + binding.localAddress.getPort

          val clientSettings = ConnectionPoolSettings(system)
            .withUpdatedConnectionSettings(
              _.withTransport(new ClientTransport {
                override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
                    implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
                  Flow[ByteString]
                    .delay(200.millis, OverflowStrategy.backpressure) // delay request sending enough to make race-condition more likely
                    .viaMat(ClientTransport.TCP.connectTo(host, port, settings))(Keep.right)
              }))

          def runRequest(clientSettings: ConnectionPoolSettings): Future[HttpResponse] =
            Http().singleRequest(HttpRequest(method = POST, uri = uri), settings = clientSettings)

          def runTest(clientSettings: ConnectionPoolSettings): Future[HttpResponse] = {
            // send first request
            runRequest(clientSettings).awaitResult(1.second)

            // delay so that next request is sent before idle-timeout of 300 millis
            // but will arrive only 200 millis later, which is after the idle-timeout
            Thread.sleep(200)

            // send second request, should run into server-side idle-timeout
            runRequest(clientSettings)
          }

          val ex = Try(runTest(clientSettings).awaitResult(1.second)).failed.get
          ex.getCause.getMessage.contains("connection closed") shouldBe true

          val clientSettings2 = clientSettings.withKeepAliveTimeout(100.millis) // < 300 millis server idle timeout
          // same test on new pool with keep-alive-timeout which should succeed
          runTest(clientSettings2).awaitResult(1.second)

          binding.unbind().awaitResult(1.second)
          Http().shutdownAllConnectionPools().awaitResult(1.second)
        }
      }
    }

    "log materialization errors in `bindFlow`".which {
      "are triggered in `mapMaterialized`" in Utils.assertAllStagesStopped {
        // FIXME racy feature, needs https://github.com/akka/akka/issues/17849 to be fixed
        pending
        val flow = Flow[HttpRequest].map(_ => HttpResponse()).mapMaterializedValue(_ => sys.error("BOOM"))
        val binding = Http(system2).newServerAt("localhost", 0).bindFlow(flow)
        val b1 = Await.result(binding, 1.seconds.dilated)

        EventFilter[RuntimeException](message = "BOOM", occurrences = 1).intercept {
          val (_, responseFuture) =
            Http(system2).outgoingConnection("localhost", b1.localAddress.getPort).runWith(Source.single(HttpRequest()),
              Sink.head)(materializer2)
          try Await.result(responseFuture, 5.seconds.dilated).status should ===(StatusCodes.InternalServerError)
          catch {
            case _: StreamTcpException =>
            // Also fine, depends on the race between abort and 500, caused by materialization panic which
            // tries to tear down everything, but the order is nondeterministic
          }
        }(system2)
        Await.result(b1.unbind(), 1.second.dilated)
      }(materializer2)

      "stop stages on failure" in Utils.assertAllStagesStopped {
        val stageCounter = new AtomicLong(0)
        val stage: GraphStage[FlowShape[HttpRequest, HttpResponse]] =
          new GraphStage[FlowShape[HttpRequest, HttpResponse]] {
            val in = Inlet[HttpRequest]("request.in")
            val out = Outlet[HttpResponse]("response.out")

            override def shape: FlowShape[HttpRequest, HttpResponse] = FlowShape.of(in, out)

            override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
              new GraphStageLogic(shape) with InHandler with OutHandler {
                override def preStart(): Unit = stageCounter.incrementAndGet()
                override def postStop(): Unit = stageCounter.decrementAndGet()
                override def onPush(): Unit = push(out, HttpResponse(entity = stageCounter.get().toString))
                override def onPull(): Unit = pull(in)

                setHandlers(in, out, this)
              }
          }

        val hostname = "127.0.0.1"
        val bind = Await.result(Http().newServerAt("127.0.0.1", 0).bindFlow(Flow.fromGraph(stage)), 1.seconds.dilated)
        val port = bind.localAddress.getPort

        def performFaultyRequest() = {
          val socket = new Socket(hostname, port)
          val os = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream, "UTF8"))

          os.write("YOLO")
          os.close()

          socket.close()
        }

        def performValidRequest() =
          Http().outgoingConnection(hostname, port).runWith(Source.single(HttpRequest()), Sink.ignore)

        def assertCounters(stage: Int) =
          eventually(timeout(3.second.dilated)) {
            stageCounter.get shouldEqual stage
          }

        performValidRequest()
        assertCounters(0)

        EventFilter.warning(pattern = "Illegal HTTP message start", occurrences = 1).intercept {
          performFaultyRequest()
          assertCounters(0)
        }

        performValidRequest()
        assertCounters(0)

        Await.result(bind.unbind(), 1.second.dilated)
      }(materializer2)
    }

    "properly complete a simple request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        clientOutSub.sendComplete()
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "properly complete a simple request/response cycle using Http.singleRequest" in Utils.assertAllStagesStopped {
      new TestSetup {
        // make sure no log message above DEBUG are printed by that exchange
        EventFilter.custom({ case l: LogEvent if l.level != Logging.DebugLevel => true }, 0).intercept {
          val settings = ConnectionPoolSettings(system).withIdleTimeout(500.millis)

          val request = HttpRequest(uri = s"http://$hostname:$port/abc")

          val responseFut = Http().singleRequest(request, settings = settings)
          val (serverIn, serverOut) = acceptConnection()

          val serverInSub = serverIn.expectSubscription()
          serverInSub.request(1)
          serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

          val serverOutSub = serverOut.expectSubscription()
          serverOutSub.expectRequest()
          serverOutSub.sendNext(HttpResponse(entity = "yeah"))

          val response = responseFut.awaitResult(1.second.dilated)
          toStrict(response.entity) shouldEqual HttpEntity("yeah")

          serverIn.expectComplete()
          serverOutSub.expectCancellation()

          binding.foreach(_.unbind())
        }
      }
    }

    "properly complete a two request/response cycles using Http.singleRequest" in Utils.assertAllStagesStopped {
      new TestSetup {
        // make sure no log message above DEBUG are printed by that exchange
        EventFilter.custom({ case l: LogEvent if l.level != Logging.DebugLevel => true }, 0).intercept {
          val settings = ConnectionPoolSettings(system).withIdleTimeout(500.millis)

          val request = HttpRequest(uri = s"http://$hostname:$port/abc?test=12")

          val responseFut = Http().singleRequest(request, settings = settings)
          val (serverIn, serverOut) = acceptConnection()

          val serverInSub = serverIn.expectSubscription()
          serverInSub.request(1)
          serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc?test=12")

          val serverOutSub = serverOut.expectSubscription()
          serverOutSub.expectRequest()
          serverOutSub.sendNext(HttpResponse(entity = "yeah"))

          val response = responseFut.awaitResult(1.second.dilated)
          toStrict(response.entity) shouldEqual HttpEntity("yeah")

          val request2 = HttpRequest(uri = s"http://$hostname:$port/abc")
          Http().singleRequest(request2, settings = settings)
          serverInSub.request(1)
          serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

          serverOutSub.sendNext(HttpResponse(entity = "yeah"))
          val response2 = responseFut.awaitResult(1.second.dilated)
          toStrict(response2.entity) shouldEqual HttpEntity("yeah")

          serverIn.expectComplete()
          serverOutSub.expectCancellation()

          binding.foreach(_.unbind())
        }
      }
    }

    "properly complete a chunked request/response cycle" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val chunks = List(Chunk("abc"), Chunk("defg"), Chunk("hijkl"), LastChunk)
        val chunkedContentType: ContentType = MediaTypes.`application/base64`.withCharset(HttpCharsets.`UTF-8`)
        val chunkedEntity = HttpEntity.Chunked(chunkedContentType, Source(chunks))

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(POST, "/chunked", List(Accept(MediaRanges.`*/*`)), chunkedEntity))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        private val HttpRequest(POST, uri, List(Accept(Seq(MediaRanges.`*/*`)), Host(_, _), `User-Agent`(_)),
          Chunked(`chunkedContentType`, chunkStream), HttpProtocols.`HTTP/1.1`) =
          serverIn.expectNext().mapHeaders(_.filterNot(_.is("timeout-access")))
        uri shouldEqual Uri(s"http://$hostname:$port/chunked")
        Await.result(chunkStream.limit(5).runWith(Sink.seq), 1000.millis.dilated) shouldEqual chunks

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(206, List(Age(42)), chunkedEntity))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val HttpResponse(StatusCodes.PartialContent, List(Age(42), Server(_), Date(_)),
          Chunked(`chunkedContentType`, chunkStream2), HttpProtocols.`HTTP/1.1`) = clientIn.expectNext()
        Await.result(chunkStream2.limit(1000).runWith(Sink.seq), 1000.millis.dilated) shouldEqual chunks

        clientOutSub.sendComplete()
        serverInSub.request(1)
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientInSub.request(1)
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }

    "complete a request/response when request has `Connection: close` set" in Utils.assertAllStagesStopped {
      // FIXME: There seems to be a potential connection leak here in the ProtocolSwitch stage?
      // https://github.com/apache/incubator-pekko-http/issues/3963
      if (http2) pending

      // In akka/akka#19542 / akka/akka-http#459 it was observed that when an akka-http closes the connection after
      // a request, the TCP connection is sometimes aborted. Aborting means that `socket.close` is called with SO_LINGER = 0
      // which removes the socket immediately from the OS network stack. This might happen with or without having sent
      // a FIN frame first and with or without actively sending a RST frame. However, if the client has not received all data
      // yet when the next ACK arrives at the server it will respond with a RST package. This will lead to a
      // broken connection and a "Connection reset by peer" error on the client.
      //
      // The original cause for connection abortion was a race between connection completion and cancellation reaching
      // each side of the Tcp connection stream.
      //
      // This reproducer tries to increase chances that bytes are still in flight when the connection is closed to trigger
      // the error more reliably.

      // The original reproducer suggested decreasing the MTU for the loopback device. We emulate a low
      // MTU by setting super small network buffers. This means more TCP round-trips between server and client
      // increasing the chances that the problem occurs.
      val serverToClientNetworkBufferSize = 1000
      val responseSize = 200000

      // settings adapting network buffer sizes
      val serverSettings =
        ServerSettings(system).withSocketOptions(SO.SendBufferSize(serverToClientNetworkBufferSize) :: Nil)
      val clientSettings = ConnectionPoolSettings(system).withConnectionSettings(ClientConnectionSettings(
        system).withSocketOptions(SO.ReceiveBufferSize(serverToClientNetworkBufferSize) :: Nil))

      def response(req: HttpRequest) = HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`,
        ByteString(req.uri.path.toString.takeRight(1) * responseSize)))

      val server = Http().newServerAt("localhost", 0).withSettings(serverSettings).bindSync(response)

      def request(i: Int) = HttpRequest(uri = s"http://localhost:${server.futureValue.localAddress.getPort}/$i",
        headers = headers.Connection("close") :: Nil)

      def runOnce(i: Int) =
        Http().singleRequest(request(i), settings = clientSettings).futureValue
          .entity.dataBytes.runFold(ByteString.empty) { (prev, cur) =>
            val res = prev ++ cur
            system.log.debug(s"Received ${res.size} of [${res.take(1).utf8String}]")
            res
          }.futureValue
          .size shouldBe responseSize

      try {
        (1 to 10).foreach(runOnce)
      } finally server.foreach(_.unbind())
      Http().shutdownAllConnectionPools().futureValue
    }

    "complete a request/response when the request side immediately closes the connection after sending the request" in Utils.assertAllStagesStopped {
      // FIXME: with HTTP/2 enabled the connection is closed directly after receiving closing from client (i.e. half-closed
      // HTTP connections are not allowed (whether they should be is a completely different question))
      // https://github.com/apache/incubator-pekko-http/issues/3964
      if (http2) pending

      val (hostname, port) = ("localhost", 8080)
      val responsePromise = Promise[HttpResponse]()

      // settings adapting network buffer sizes
      val serverSettings = ServerSettings(system)

      val server =
        Http().newServerAt(hostname, port).withSettings(serverSettings).bind(_ => responsePromise.future).futureValue

      try {
        val result = Source.single(ByteString(
          """GET / HTTP/1.1
Host: example.com

"""))
          .via(Tcp().outgoingConnection(hostname, port))
          .runWith(Sink.reduce[ByteString](_ ++ _))
        Try(Await.result(result, 2.seconds).utf8String) match {
          case scala.util.Success(body)                => fail(body)
          case scala.util.Failure(_: TimeoutException) => // Expected
          case scala.util.Failure(other)               => fail(other)
        }
      } finally {
        responsePromise.failure(new TimeoutException())
        server.unbind()
      }
    }

    "complete a request/response over https, disabling hostname verification with SSLConfigSettings" in Utils.assertAllStagesStopped {
      val serverConnectionContext = ExampleHttpContexts.exampleServerContext
      val handlerFlow: Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest].map { _ =>
        HttpResponse(entity = "Okay")
      }
      val serverBinding =
        Http().newServerAt("localhost", 0).enableHttps(serverConnectionContext).bindFlow(handlerFlow)
          .futureValue

      // Disable hostname verification as ExampleHttpContexts.exampleClientContext sets hostname as pekko.example.org
      val sslConfigSettings = SSLConfigSettings().withLoose(SSLLooseConfig().withDisableHostnameVerification(true))
      val sslConfig = PekkoSSLConfig().withSettings(sslConfigSettings)
      val sslContext = {
        val certStore = KeyStore.getInstance(KeyStore.getDefaultType)
        certStore.load(null, null)
        // only do this if you want to accept a custom root CA. Understand what you are doing!
        certStore.setCertificateEntry("ca", loadX509Certificate("keys/rootCA.crt"))

        val certManagerFactory = TrustManagerFactory.getInstance("SunX509")
        certManagerFactory.init(certStore)

        val context = SSLContext.getInstance("TLSv1.2")
        context.init(null, certManagerFactory.getTrustManagers, new SecureRandom)
        context
      }

      // This approach is deprecated, but we still want to check it works
      @nowarn("msg=deprecated")
      val clientConnectionContext = ConnectionContext.https(sslContext, Some(sslConfig))

      val request = HttpRequest(uri = s"https:/${serverBinding.localAddress}")
      Http()
        .singleRequest(request, connectionContext = clientConnectionContext)
        .futureValue
        .entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String shouldEqual "Okay"

      serverBinding.unbind().futureValue
      Http().shutdownAllConnectionPools().futureValue
    }

    "complete a request/response over https when request has `Connection: close` set" in Utils.assertAllStagesStopped {
      // akka/akka-http#1219
      val serverToClientNetworkBufferSize = 1000
      val request = HttpRequest(uri = s"https://pekko.example.org", headers = headers.Connection("close") :: Nil)

      // settings adapting network buffer sizes
      val serverSettings =
        ServerSettings(system)
          .withSocketOptions(SO.SendBufferSize(serverToClientNetworkBufferSize) :: Nil)

      val serverConnectionContext = ExampleHttpContexts.exampleServerContext

      val entity = Array.fill[Char](999999)('0').mkString + "x"
      val handlerFlow: Flow[HttpRequest, HttpResponse, Any] = Flow[HttpRequest].map { _ =>
        HttpResponse(entity = entity)
      }
      val serverBinding =
        Http().newServerAt("localhost", 0).enableHttps(serverConnectionContext).withSettings(serverSettings).bindFlow(
          handlerFlow)
          .futureValue

      // settings adapting network buffer sizes, and connecting to the spun-up server regardless of the URI address
      val clientSettings = ConnectionPoolSettings(system)
        .withConnectionSettings(
          ClientConnectionSettings(system)
            .withSocketOptions(SO.ReceiveBufferSize(serverToClientNetworkBufferSize) :: Nil))
        .withTransport(ExampleHttpContexts.proxyTransport(serverBinding.localAddress))

      val clientConnectionContext = ExampleHttpContexts.exampleClientContext

      Http()
        .singleRequest(request, connectionContext = clientConnectionContext, settings = clientSettings)
        .futureValue
        .entity.dataBytes
        .mapAsync(1) { chunk =>
          // delay reading chunks on the client side to create more backpressure
          // to reproduce problems more reliably
          pekko.pattern.after(10.millis, system.scheduler)(Future.successful(chunk))
        }.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String shouldEqual entity

      serverBinding.unbind().futureValue
      Http().shutdownAllConnectionPools().futureValue
    }

    class CloseDelimitedTLSSetup {
      val source = TestPublisher.probe[ByteString]()

      def handler(req: HttpRequest): HttpResponse =
        HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`application/octet-stream`,
          Source.fromPublisher(source)))

      val serverSideTls = Http().sslTlsServerStage(ExampleHttpContexts.exampleServerContext)
      val clientSideTls = Http().sslTlsClientStage(ExampleHttpContexts.exampleClientContext, "pekko.example.org", 8080)

      val server: Flow[ByteString, ByteString, Any] =
        Http().serverLayer()
          .atop(serverSideTls)
          .reversed
          .join(Flow[HttpRequest].map(handler))

      val client =
        Http().clientLayer(Host("pekko.example.org", 8080))
          .atop(clientSideTls)

      val killSwitch = KillSwitches.shared("kill-transport")

      val pipe: Flow[HttpRequest, HttpResponse, Any] =
        client
          .atop(BidiFlow.fromFlows(Flow[ByteString], killSwitch.flow[ByteString])) // kill switch will kill server -> client connection without close_notify
          .join(server)

      val response =
        Source.single(HttpRequest())
          .via(pipe)
          .runWith(Sink.head)
          .awaitResult(10.seconds)

      val sinkProbe = ByteStringSinkProbe()
      response.entity.dataBytes.runWith(sinkProbe.sink)

      source.sendNext(ByteString("abcdef"))
      sinkProbe.expectUtf8EncodedString("abcdef")

      source.sendNext(ByteString("ghij"))
      sinkProbe.expectUtf8EncodedString("ghij")
    }

    "complete a request/response with CloseDelimited entity over TLS" in Utils.assertAllStagesStopped {
      new CloseDelimitedTLSSetup {
        source.sendComplete()
        sinkProbe.expectComplete()
      }
    }

    "complete a request/response over https when server closes connection without close_notify" in Utils.assertAllStagesStopped {
      new CloseDelimitedTLSSetup {
        killSwitch.shutdown() // simulate FIN in server -> client direction
        // pekko-http is currently lenient wrt TLS truncation which is *not* reported to the user
        // FIXME: if https://github.com/apache/incubator-pekko-http/issues/235 is ever fixed, expect an error here
        sinkProbe.expectComplete()
      }
    }

    "properly complete a simple request/response cycle when `modeled-header-parsing = off`" in Utils.assertAllStagesStopped {
      new TestSetup {
        override def configOverrides = "pekko.http.parsing.modeled-header-parsing = off"

        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        clientOutSub.sendComplete()
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "properly complete a simple request/response cycle when `max-content-length` is set to 0" in Utils.assertAllStagesStopped {
      new TestSetup {
        override def configOverrides = """
            pekko.http.client.parsing.max-content-length = 0
            pekko.http.server.parsing.max-content-length = 0
        """

        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.expectRequest()
        clientOutSub.sendNext(HttpRequest(uri = "/abc", entity = ""))

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = ""))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("")

        clientOutSub.sendComplete()
        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        binding.foreach(_.unbind())
      }
    }

    "be able to deal with eager closing of the request stream on the client side" in Utils.assertAllStagesStopped {
      new TestSetup {
        val (clientOut, clientIn) = openNewClientConnection()
        val (serverIn, serverOut) = acceptConnection()

        val clientOutSub = clientOut.expectSubscription()
        clientOutSub.sendNext(HttpRequest(uri = "/abc"))
        clientOutSub.sendComplete()
        // complete early

        val serverInSub = serverIn.expectSubscription()
        serverInSub.request(1)
        serverIn.expectNext().uri shouldEqual Uri(s"http://$hostname:$port/abc")

        val serverOutSub = serverOut.expectSubscription()
        serverOutSub.expectRequest()
        serverOutSub.sendNext(HttpResponse(entity = "yeah"))

        val clientInSub = clientIn.expectSubscription()
        clientInSub.request(1)
        val response = clientIn.expectNext()
        toStrict(response.entity) shouldEqual HttpEntity("yeah")

        serverIn.expectComplete()
        serverOutSub.expectCancellation()
        clientIn.expectComplete()

        connSourceSub.cancel()
      }
    }

    "produce a useful error message when connecting to a HTTP endpoint over HTTPS" in Utils.assertAllStagesStopped {
      // FIXME: it would be better if this wouldn't be necessary, see https://github.com/apache/incubator-pekko-http/issues/3159#issuecomment-628605844
      val settings = ConnectionPoolSettings(system).withUpdatedConnectionSettings(_.withIdleTimeout(100.millis))
      val dummyFlow = Flow[HttpRequest].map(_ => ???)

      val binding = Http().newServerAt("127.0.0.1", 0).bindFlow(dummyFlow).futureValue
      val uri = "https://" + binding.localAddress.getHostString + ":" + binding.localAddress.getPort

      EventFilter.warning(pattern = "Perhaps this was an HTTPS request sent to an HTTP endpoint",
        occurrences = 1).intercept {
        // Test with a POST so auto-retry isn't triggered:
        Await.ready(Http().singleRequest(HttpRequest(uri = uri, method = HttpMethods.POST), settings = settings),
          30.seconds)
      }

      Await.result(binding.unbind(), 10.seconds)
      Http().shutdownAllConnectionPools().futureValue
    }

    "produce a useful error message when connecting to an endpoint speaking wrong protocol" in Utils.assertAllStagesStopped {
      val settings = ConnectionPoolSettings(system).withUpdatedConnectionSettings(_.withIdleTimeout(100.millis))

      val binding =
        Tcp().bindAndHandle(Flow[ByteString].map(_ => ByteString("hello world!")), "127.0.0.1", 0).futureValue
      val uri = "http://" + binding.localAddress.getHostString + ":" + binding.localAddress.getPort

      val ex = the[IllegalResponseException] thrownBy Await.result(Http().singleRequest(HttpRequest(uri = uri,
        method = HttpMethods.POST), settings = settings), 30.seconds)
      ex.info.formatPretty shouldEqual "The server-side protocol or HTTP version is not supported: start of response: [68 65 6C 6C 6F 20 77 6F 72 6C 64 21              | hello world!]"

      Await.result(binding.unbind(), 10.seconds)
      Http().shutdownAllConnectionPools().futureValue
    }

    "report idle timeout on request entity stream for stalled client" in Utils.assertAllStagesStopped {
      val dataProbe = ByteStringSinkProbe()

      def handler(request: HttpRequest): Future[HttpResponse] = {
        request.entity.dataBytes.runWith(dataProbe.sink)
        Promise[HttpResponse]().future // just let it hanging until idle timeout triggers
      }

      val settings = ServerSettings(system).mapTimeouts(_.withIdleTimeout(1.second))
      val binding = Http().newServerAt("127.0.0.1", 0).withSettings(settings).bind(handler).futureValue
      val uri = "http://" + binding.localAddress.getHostString + ":" + binding.localAddress.getPort

      val dataOutProbe = TestPublisher.probe[ByteString]()
      Http().singleRequest(HttpRequest(uri = uri,
        entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(dataOutProbe))))

      dataProbe.ensureSubscription()
      dataOutProbe.sendNext(ByteString("test"))
      dataProbe.expectUtf8EncodedString("test")
      dataProbe.expectError() should be(an[HttpIdleTimeoutException])

      binding.unbind().futureValue
      Http().shutdownAllConnectionPools().futureValue
    }
  }

  override def beforeTermination() = {
    TestKit.shutdownActorSystem(system2)
  }

  class TestSetup {
    val hostname = "localhost"
    def configOverrides = ""

    // automatically bind a server
    val (connSource, binding: Future[ServerBinding]) = {
      val settings = configOverrides.toOption.fold(ServerSettings(system))(ServerSettings(_))
      val connections = Http().newServerAt(hostname, 0).withSettings(settings).connectionSource()
      val probe = TestSubscriber.manualProbe[Http.IncomingConnection]()
      val binding = connections.to(Sink.fromSubscriber(probe)).run()
      (probe, binding)
    }
    val port = binding.futureValue.localAddress.getPort
    val connSourceSub = connSource.expectSubscription()

    def openNewClientConnection(settings: ClientConnectionSettings = ClientConnectionSettings(system)) = {
      val requestPublisherProbe = TestPublisher.manualProbe[HttpRequest]()
      val responseSubscriberProbe = TestSubscriber.manualProbe[HttpResponse]()

      val connectionFuture = Source.fromPublisher(requestPublisherProbe)
        .viaMat(Http().outgoingConnection(hostname, port, settings = settings))(Keep.right)
        .to(Sink.fromSubscriber(responseSubscriberProbe)).run()

      val connection = Await.result(connectionFuture, 3.seconds.dilated)

      connection.remoteAddress.getHostName shouldEqual hostname
      connection.remoteAddress.getPort shouldEqual port
      requestPublisherProbe -> responseSubscriberProbe
    }

    def acceptConnection(): (TestSubscriber.ManualProbe[HttpRequest], TestPublisher.ManualProbe[HttpResponse]) = {
      connSourceSub.request(1)
      val incomingConnection = connSource.expectNext()
      val sink = Sink.asPublisher[HttpRequest](false)
      val source = Source.asSubscriber[HttpResponse]

      val handler = Flow.fromSinkAndSourceMat(sink, source)(Keep.both)

      val (pub, sub) = incomingConnection.handleWith(handler)
      val requestSubscriberProbe = TestSubscriber.manualProbe[HttpRequest]()
      val responsePublisherProbe = TestPublisher.manualProbe[HttpResponse]()

      pub.subscribe(requestSubscriberProbe)
      responsePublisherProbe.subscribe(sub)
      requestSubscriberProbe -> responsePublisherProbe
    }

    def openClientSocket() = new Socket(hostname, port)

    def write(socket: Socket, data: String) = {
      val writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream))
      writer.write(data)
      writer.flush()
      writer
    }

    def readAll(socket: Socket)(
        reader: BufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream)))
        : (String, BufferedReader) = {
      val sb = new java.lang.StringBuilder
      val cbuf = new Array[Char](256)
      @tailrec def drain(): (String, BufferedReader) = reader.read(cbuf) match {
        case -1 => sb.toString -> reader
        case n  => sb.append(cbuf, 0, n); drain()
      }
      drain()
    }
  }

  def toStrict(entity: HttpEntity): HttpEntity.Strict =
    Await.result(entity.toStrict(500.millis.dilated), 1.second.dilated)
}
