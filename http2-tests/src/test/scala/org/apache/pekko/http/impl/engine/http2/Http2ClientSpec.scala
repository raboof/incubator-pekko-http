/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.impl.engine.http2

import org.apache.pekko
import pekko.NotUsed
import pekko.event.Logging
import pekko.http.impl.engine.http2.FrameEvent._
import pekko.http.impl.engine.http2.Http2Protocol.ErrorCode
import pekko.http.impl.engine.http2.Http2Protocol.FrameType
import pekko.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import pekko.http.impl.engine.server.HttpAttributes
import pekko.http.impl.engine.ws.ByteStringSinkProbe
import pekko.http.impl.util.PekkoSpecWithMaterializer
import pekko.http.impl.util.LogByteStringTools
import pekko.http.scaladsl.client.RequestBuilding.Get
import pekko.http.scaladsl.client.RequestBuilding.Post
import pekko.http.scaladsl.model.HttpEntity.Chunk
import pekko.http.scaladsl.model.HttpEntity.ChunkStreamPart
import pekko.http.scaladsl.model.HttpEntity.Chunked
import pekko.http.scaladsl.model.HttpEntity.LastChunk
import pekko.http.scaladsl.model.HttpMethods.GET
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.CacheDirectives._
import pekko.http.scaladsl.model.headers.{
  `Access-Control-Allow-Origin`,
  `Cache-Control`,
  `Content-Length`,
  `Content-Type`,
  RawHeader
}
import pekko.http.scaladsl.settings.ClientConnectionSettings
import pekko.stream.Attributes
import pekko.stream.Attributes.LogLevels
import pekko.stream.scaladsl.BidiFlow
import pekko.stream.scaladsl.Flow
import pekko.stream.scaladsl.Sink
import pekko.stream.scaladsl.Source
import pekko.stream.testkit.TestPublisher
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.StreamTestKit
import pekko.stream.testkit.scaladsl.TestSink
import pekko.testkit.EventFilter
import pekko.testkit.TestDuration
import pekko.util.ByteString
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import javax.net.ssl.SSLContext
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * This tests the http2 client protocol logic.
 *
 * Tests typically:
 * * provide outgoing application-level requests
 * * if applicable: validate the constructed outgoing frames
 * * if applicable: provide response frames
 * * validate the produced application-level responses
 */
class Http2ClientSpec extends PekkoSpecWithMaterializer("""
    pekko.http.client.remote-address-header = on
    pekko.http.client.http2.log-frames = on
    pekko.http.client.http2.completion-timeout = 500ms
  """)
    with WithInPendingUntilFixed with Eventually {

  override implicit val patience = PatienceConfig(5.seconds, 5.seconds)
  override def failOnSevereMessages: Boolean = true

  "The Http/2 client implementation" should {
    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with NetProbes {

        val defaultExpectedHeaders = Seq(
          ":method" -> "GET",
          ":scheme" -> "https",
          ":authority" -> "www.example.com",
          ":path" -> "/",
          "content-length" -> "0",
          "user-agent" -> ClientConnectionSettings(system).userAgentHeader.get.value)

        def requestResponseRoundtrip(
            streamId: Int,
            request: HttpRequest,
            expectedHeaders: Seq[(String, String)],
            response: Seq[FrameEvent],
            expectedResponse: HttpResponse): Unit = {
          user.emitRequest(request)

          network.expectDecodedResponseHEADERSPairs(streamId) should contain theSameElementsAs (
            expectedHeaders.filter(_._1 != "date"))
          response.foreach(network.sendFrame)

          val receivedResponse = user.expectResponse()
          receivedResponse.status shouldBe expectedResponse.status
          receivedResponse.headers shouldBe expectedResponse.headers
          receivedResponse.entity.contentType shouldBe expectedResponse.entity.contentType
          receivedResponse.entity.dataBytes.runFold(ByteString())(
            _ ++ _).futureValue shouldBe expectedResponse.entity.dataBytes.runFold(ByteString())(_ ++ _).futureValue
        }
      }

      "GET request in one HEADERS frame".inAssertAllStagesStopped(new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(uri = "https://www.example.com/"),
          expectedHeaders = defaultExpectedHeaders,
          response = Seq(
            // TODO shouldn't this produce an error since stream '1' is already the outgoing stream?
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
              HPackSpecExamples.C61FirstResponseWithHuffman, None)),
          expectedResponse = HPackSpecExamples.FirstResponse)
      })

      "GOAWAY when the response has an invalid headers frame".inAssertAllStagesStopped(new TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(HttpRequest(uri = "http://www.example.com/"))
        network.expect[HeadersFrame]()

        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        network.sendFrame(HeadersFrame(streamId, endStream = true, endHeaders = true, headerBlock, None))

        val (_, error) = network.expectGOAWAY(1)
        error should ===(ErrorCode.COMPRESSION_ERROR)

        // TODO we'd expect an error response here I think? We don't get any reply though...
      })

      "GOAWAY when the response to a second request on different stream has an invalid headers frame".inAssertAllStagesStopped(
        new SimpleRequestResponseRoundtripSetup {
          requestResponseRoundtrip(
            streamId = 1,
            request = HttpRequest(uri = "https://www.example.com/"),
            expectedHeaders = defaultExpectedHeaders,
            response = Seq(
              HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
                HPackSpecExamples.C61FirstResponseWithHuffman, None)),
            expectedResponse = HPackSpecExamples.FirstResponse)

          user.emitRequest(HttpRequest(uri = "https://www.example.com/"))
          network.expect[HeadersFrame]()

          val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
          network.sendHEADERS(3, endStream = true, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

          val (_, errorCode) = network.expectGOAWAY(3)
          errorCode should ===(ErrorCode.COMPRESSION_ERROR)
        })

      "GOAWAY when the response has headers mid stream".inAssertAllStagesStopped(new TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(Get("/"))
        network.expectDecodedHEADERS(streamId, endStream = true)

        network.sendHEADERS(streamId, endStream = false,
          Seq(
            RawHeader(":status", "200"),
            RawHeader("content-type", "application/octet-stream")))

        val response = user.expectResponse()
        response.entity shouldBe a[Chunked]

        network.sendDATA(streamId, endStream = false, ByteString("asdf"))
        network.expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE)

        network.sendHEADERS(streamId, endStream = false, Seq(RawHeader("X-mid-stream", "badvalue")))

        val goAwayFrame = network.expect[GoAwayFrame]()
        goAwayFrame.errorCode should ===(ErrorCode.PROTOCOL_ERROR)
        goAwayFrame.debug.utf8String should ===("Got unexpected mid-stream HEADERS frame")
      })

      "Three consecutive GET requests".inAssertAllStagesStopped(new SimpleRequestResponseRoundtripSetup {
        import pekko.http.scaladsl.model.headers.CacheDirectives._
        import headers.`Cache-Control`
        val requestHeaders = defaultExpectedHeaders
        requestResponseRoundtrip(
          streamId = 1,
          request = HttpRequest(GET, "https://www.example.com/"),
          expectedHeaders = requestHeaders,
          response = Seq(
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
              HPackSpecExamples.C61FirstResponseWithHuffman, None)),
          expectedResponse = HPackSpecExamples.FirstResponse)
        requestResponseRoundtrip(
          streamId = 3,
          request = HttpRequest(GET, uri = "https://www.example.com/", Vector(`Cache-Control`(`no-cache`))),
          expectedHeaders = requestHeaders :+ ("cache-control" -> "no-cache"),
          response = Seq(
            HeadersFrame(streamId = 3, endStream = true, endHeaders = true,
              HPackSpecExamples.C62SecondResponseWithHuffman, None)),
          expectedResponse = HPackSpecExamples.SecondResponse)
        requestResponseRoundtrip(
          streamId = 5,
          request =
            HttpRequest(HttpMethods.GET, "https://www.example.com/", Vector(RawHeader("custom-key", "custom-value"))),
          expectedHeaders = requestHeaders :+ ("custom-key" -> "custom-value"),
          response = Seq(
            HeadersFrame(streamId = 5, endStream = true, endHeaders = true,
              HPackSpecExamples.C63ThirdResponseWithHuffman, None)),
          expectedResponse = HPackSpecExamples.ThirdResponseModeled)
      })

      "accept response with one HEADERS and one CONTINUATION frame".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          user.emitRequest(Get("https://www.example.com/"))
          network.expect[HeadersFrame]()

          val headerBlock = HPackSpecExamples.C61FirstResponseWithHuffman
          val fragment1 = headerBlock.take(8) // must be grouped by octets
          val fragment2 = headerBlock.drop(8)
          network.sendHEADERS(0x1, endStream = true, endHeaders = false, fragment1)
          network.sendCONTINUATION(0x1, endHeaders = true, fragment2)
          user.expectResponse().headers should be(HPackSpecExamples.FirstResponse.headers)
        })

      "accept response with one HEADERS and two CONTINUATION frames".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          user.emitRequest(Get("https://www.example.com/"))
          network.expect[HeadersFrame]()

          val headerBlock = HPackSpecExamples.C61FirstResponseWithHuffman
          val fragment1 = headerBlock.take(8) // must be grouped by octets
          val fragment2 = headerBlock.drop(8).take(8)
          val fragment3 = headerBlock.drop(16)
          network.sendHEADERS(0x1, endStream = true, endHeaders = false, fragment1)
          network.sendCONTINUATION(0x1, endHeaders = false, fragment2)
          network.sendCONTINUATION(0x1, endHeaders = true, fragment3)
          user.expectResponse().headers should be(HPackSpecExamples.FirstResponse.headers)
        })

      "automatically add `date` header".inAssertAllStagesStopped(new TestSetup with NetProbes {
        user.emitRequest(Get("https://www.example.com/"))
        network.expectDecodedHEADERS(0x1, endStream = true).headers.exists(_.is("date"))
      })

      "parse headers to modeled headers".inAssertAllStagesStopped(new TestSetup with NetProbes {
        user.emitRequest(Get("https://www.example.com/"))
        network.expect[HeadersFrame]()

        network.sendHEADERS(0x1, true,
          Seq(
            RawHeader(":status", "401"),
            RawHeader("cache-control", "no-cache"),
            RawHeader("cache-control", "max-age=1000"),
            RawHeader("access-control-allow-origin", "*")))

        val response = user.expectResponse()
        response.status should be(StatusCodes.Unauthorized)
        response.headers should contain(`Cache-Control`(`no-cache`))
        response.headers should contain(`Cache-Control`(`max-age`(1000)))
        response.headers should contain(`Access-Control-Allow-Origin`.`*`)
      })

      "acknowledge change to SETTINGS_HEADER_TABLE_SIZE in next HEADER frame".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          network.sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, 8192)
          network.expectSettingsAck()

          user.emitRequest(Get("/"))
          val headerPayload = network.expectHeaderBlock(1)

          // Dynamic Table Size Update (https://tools.ietf.org/html/rfc7541#section-6.3) is
          //
          // 0   1   2   3   4   5   6   7
          // +---+---+---+---+---+---+---+---+
          // | 0 | 0 | 1 |   Max size (5+)   |
          // +---+---------------------------+
          //
          // 8192 is 10000000000000 = 14 bits, i.e. needs more than 4 bits
          // 8192 - 16 = 8176 = 111111 1110000
          // 001 11111 = 63
          // 1 1110000 = 225
          // 0 0111111 = 63

          val dynamicTableUpdateTo8192 = ByteString(63, 225, 63)
          headerPayload.take(3) shouldBe dynamicTableUpdateTo8192
        })
    }

    "support stream for response data" should {
      abstract class WaitingForResponse extends TestSetup with NetProbes {
        user.emitRequest(Get("/"))
        val TheStreamId = network.expect[HeadersFrame]().streamId
      }
      abstract class WaitingForResponseData extends WaitingForResponse {
        network.sendHEADERS(TheStreamId, endStream = false, Seq(RawHeader(":status", "200")))
        val entityDataIn = ByteStringSinkProbe(user.expectResponse().entity.dataBytes)
      }
      "send data frames to entity stream".inAssertAllStagesStopped(new WaitingForResponseData {
        val data1 = ByteString("abcdef")
        network.sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        val data2 = ByteString("zyxwvu")
        network.sendDATA(TheStreamId, endStream = false, data2)
        entityDataIn.expectBytes(data2)

        val data3 = ByteString("mnopq")
        network.sendDATA(TheStreamId, endStream = true, data3)
        entityDataIn.expectBytes(data3)
        entityDataIn.expectComplete()
      })
      "handle content-length and content-type of incoming response".inAssertAllStagesStopped(new WaitingForResponse {
        network.sendHEADERS(TheStreamId, endStream = false,
          Seq(
            RawHeader(":status", "200"),
            `Content-Type`(ContentTypes.`application/json`),
            `Content-Length`(2000)))

        val response = user.expectResponse()
        response.entity.contentType should ===(ContentTypes.`application/json`)

        // FIXME: contentLength is not reported in all cases with HTTP/2
        // see https://github.com/apache/incubator-pekko-http/issues/3843
        // response.entity.isIndefiniteLength should ===(false)
        // response.entity.contentLengthOption should ===(Some(2000L))

        network.sendDATA(TheStreamId, endStream = false, ByteString("x" * 1000))
        network.sendDATA(TheStreamId, endStream = true, ByteString("x" * 1000))

        val entityDataIn = ByteStringSinkProbe(response.entity.dataBytes)
        entityDataIn.expectBytes(2000)
        entityDataIn.expectComplete()
      })
      "fail entity stream if peer sends RST_STREAM frame".inAssertAllStagesStopped(new WaitingForResponseData {
        val data1 = ByteString("abcdef")
        network.sendDATA(TheStreamId, endStream = false, data1)

        entityDataIn.expectBytes(data1)

        network.sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
        val error = entityDataIn.expectError()
        error.getMessage shouldBe "Stream with ID [1] was closed by peer with code INTERNAL_ERROR(0x02)"
      })
      "not fail the whole connection when one stream is RST twice".inAssertAllStagesStopped(new WaitingForResponseData {
        network.sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
        val error = entityDataIn.expectError()
        error.getMessage shouldBe "Stream with ID [1] was closed by peer with code INTERNAL_ERROR(0x02)"

        // https://http2.github.io/http2-spec/#StreamStates
        // Endpoints MUST ignore WINDOW_UPDATE or RST_STREAM frames received in this state,
        network.sendRST_STREAM(TheStreamId, ErrorCode.STREAM_CLOSED)

        connectionShouldStillBeUsable()
      })
      "not fail the whole connection when data frames are received after stream was cancelled".inAssertAllStagesStopped(
        new WaitingForResponseData {
          entityDataIn.cancel()
          network.expectRST_STREAM(TheStreamId)

          network.sendDATA(TheStreamId, endStream = false, ByteString("test"))

          connectionShouldStillBeUsable()
        })
      "send RST_STREAM if entity stream is canceled".inAssertAllStagesStopped(new WaitingForResponseData {
        val data1 = ByteString("abcdef")
        network.sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        network.pollForWindowUpdates(10.millis)

        entityDataIn.cancel()
        network.expectRST_STREAM(TheStreamId, ErrorCode.CANCEL)
      })
      "send out WINDOW_UPDATE frames when request data is read so that the stream doesn't stall".inAssertAllStagesStopped(
        new WaitingForResponseData {
          (1 to 10).foreach { _ =>
            val bytesSent = network.sendWindowFullOfData(TheStreamId)
            bytesSent should be > 0
            entityDataIn.expectBytes(bytesSent)
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) should be > 0
          }
        })
      "backpressure until response entity stream is read (don't send out unlimited WINDOW_UPDATE before)".inAssertAllStagesStopped(
        new WaitingForResponseData {
          var totallySentBytes = 0
          // send data until we don't receive any window updates from the implementation any more
          eventually(Timeout(1.second.dilated)) {
            totallySentBytes += network.sendWindowFullOfData(TheStreamId)
            // the implementation may choose to send a few window update until internal buffers are filled
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) shouldBe 0
          }

          // now drain entity source
          entityDataIn.expectBytes(totallySentBytes)

          eventually(Timeout(1.second.dilated)) {
            network.pollForWindowUpdates(10.millis)
            network.remainingWindowForIncomingData(TheStreamId) should be > 0
          }
        })
      "send data frames to entity stream and ignore trailing headers".inAssertAllStagesStopped(
        new WaitingForResponseData {
          val data1 = ByteString("abcdef")
          network.sendDATA(TheStreamId, endStream = false, data1)
          entityDataIn.expectBytes(data1)

          network.sendHEADERS(TheStreamId, endStream = true, Seq(RawHeader(":grpc-status", "0")))
          // This '.request(1)' can be removed when we move to Akka 2.6, since that has
          // https://github.com/akka/akka/pull/28467
          entityDataIn.request(1)
          entityDataIn.expectComplete()
        })
      "send data frames to entity stream and consume trailing headers".inAssertAllStagesStopped(new WaitingForResponse {
        network.sendHEADERS(TheStreamId, endStream = false, Seq(RawHeader(":status", "200")))
        val chunksIn =
          user.expectResponse()
            .entity.asInstanceOf[Chunked]
            .chunks.runWith(TestSink.probe[ChunkStreamPart](system.classicSystem))
        val data1 = ByteString("abcdef")
        network.sendDATA(TheStreamId, endStream = false, data1)
        chunksIn.request(2)
        chunksIn.expectNext() should be(Chunk(data1))

        network.sendHEADERS(TheStreamId, endStream = true, Seq(RawHeader("grpc-status", "0")))

        val last = chunksIn.expectNext()
        last.asInstanceOf[LastChunk].trailer.head should be(RawHeader("grpc-status", "0"))

        chunksIn.expectComplete()
      })
      "fail entity stream if advertised content-length doesn't match" in pending
    }

    "support streaming for sending request entity data" should {
      abstract class WaitingForRequestData extends TestSetup {
        val entityDataOut = TestPublisher.probe[ByteString]()
        user.emitRequest(Post("/",
          HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut))))
        val TheStreamId = network.expect[HeadersFrame]().streamId
      }
      "encode Content-Length and Content-Type headers".inAssertAllStagesStopped(new TestSetup {
        val request = Post("/", HttpEntity(ContentTypes.`application/octet-stream`, ByteString("abcde")))
        user.emitRequest(request)
        val pairs = network.expectDecodedResponseHEADERSPairs(streamId = 0x1, endStream = false).toMap
        pairs should contain(":method" -> "POST")
        pairs should contain("content-length" -> "5")
        pairs should contain("content-type" -> "application/octet-stream")
      })
      "send entity data as data frames".inAssertAllStagesStopped(new WaitingForRequestData {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        network.expectDATA(TheStreamId, endStream = false, data1)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)
        network.expectDATA(TheStreamId, endStream = false, data2)

        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      })
      "parse priority frames".inAssertAllStagesStopped(new WaitingForRequestData {
        network.sendPRIORITY(TheStreamId, exclusiveFlag = true, 0, 5)
        entityDataOut.sendComplete()
        network.expectDATA(TheStreamId, endStream = true, ByteString.empty)
      })
      "cancel entity data source when peer sends RST_STREAM".inAssertAllStagesStopped(new WaitingForRequestData {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        network.expectDATA(TheStreamId, endStream = false, data1)

        network.sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)
        entityDataOut.expectCancellation()

        connectionShouldStillBeUsable()
      })
      "handle RST_STREAM while waiting for a window update".inAssertAllStagesStopped(new WaitingForRequestData {
        val entitySize = 70000
        entityDataOut.sendNext(ByteString(Array.fill[Byte](entitySize)(0x23))) // 70000 > Http2Protocol.InitialWindowSize
        network.sendWINDOW_UPDATE(TheStreamId, 10000) // enough window for the stream but not for the window

        network.expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        // enough stream-level WINDOW, but too little connection-level WINDOW
        network.expectNoBytes(100.millis)

        // now the demuxer is in the WaitingForConnectionWindow state, cancel the connection
        network.sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)

        entityDataOut.expectCancellation()
        network.expectNoBytes(100.millis)

        // now increase connection-level window again and see if everything still works
        network.sendWINDOW_UPDATE(0, 10000)
        network.expectNoBytes(100.millis) // don't expect anything, stream has been cancelled in the meantime

        connectionShouldStillBeUsable()
      })
      "handle unknown frames while waiting for a window update".inAssertAllStagesStopped(new WaitingForRequestData {
        user.emitRequest(Get("/secondRequest"))
        val otherRequestStreamId = network.expect[HeadersFrame]().streamId

        val entitySize = 70000
        entityDataOut.sendNext(ByteString(Array.fill[Byte](entitySize)(0x23))) // 70000 > Http2Protocol.InitialWindowSize
        network.sendWINDOW_UPDATE(TheStreamId, 10000) // enough window for the stream but not for the window

        network.expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        // enough stream-level WINDOW, but too little connection-level WINDOW
        network.expectNoBytes(100.millis)

        // now the stream handler is in the OpenSendingData state, and waiting for the
        // response headers, unexpectedly send response data:
        network.sendDATA(TheStreamId, endStream = false, ByteString("surprise!"))

        entityDataOut.expectCancellation()
        network.expectGOAWAY(otherRequestStreamId)

        // make sure the demuxer also moved back from WaitingForConnectionWindow to Idle
        network.sendWINDOW_UPDATE(0, 10000)
        network.expectNoBytes(100.millis) // don't expect anything, stream has been cancelled in the meantime

        // TODO the client stack should not accept new connections anymore
        // TODO what to do with requests that are already in the queue at this point?
        // user.requestOut.expectCancellation()

        // Check finishing old requests is still allowed
        network.sendHEADERS(otherRequestStreamId, true, Seq(RawHeader(":status", "200")))
        user.expectResponse()
      })

      abstract class StreamingRequestSent extends TestSetup {
        val streamId = 0x1
        val requestStream = TestPublisher.probe[ByteString]()
        user.emitRequest(HttpRequest(entity = HttpEntity(ContentTypes.`application/octet-stream`,
          Source.fromPublisher(requestStream))))
        network.expectDecodedHEADERS(streamId, endStream = false)
        requestStream.expectRequest()
      }
      "send RST_STREAM when request entity data stream fails immediately" in new StreamingRequestSent {
        EventFilter[RuntimeException](pattern = "Substream 1 failed with .*", occurrences = 1).intercept {
          requestStream.sendError(new RuntimeException("boom"))
          network.expectRST_STREAM(streamId, ErrorCode.INTERNAL_ERROR)
        }
      }

      "send RST_STREAM when request entity data stream fails" in new StreamingRequestSent {
        requestStream.sendNext(ByteString("abc"))
        network.expectDATA(streamId, false, ByteString("abc"))

        EventFilter[RuntimeException](pattern = "Substream 1 failed with .*", occurrences = 1).intercept {
          requestStream.sendError(new RuntimeException("boom"))
          network.expectRST_STREAM(streamId, ErrorCode.INTERNAL_ERROR)
        }
      }
    }

    "respect flow-control" should {
      "accept window updates when done sending the request".inAssertAllStagesStopped(new TestSetup {
        user.emitRequest(Get("/"))
        network.expectDecodedHEADERS(0x1, endStream = true)

        // Server randomly sends a window update even though we're already done sending the request,
        // which may happen since window updating is asynchronous:
        network.sendWINDOW_UPDATE(0x1, 20)
        network.sendHEADERS(0x1, endStream = true, endHeaders = true,
          network.encodeHeaderPairs(Seq((":status", "200"))))
        user.expectResponse()
      })
    }

    "send settings" should {
      abstract class SettingsSetup extends TestSetupWithoutHandshake with NetProbes {
        def expectSetting(expected: Setting): Unit = {
          network.toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
          network.expectSETTINGS().settings should contain(expected)
        }
      }

      "disable Push via SETTINGS_ENABLE_PUSH" in new SettingsSetup {
        expectSetting(Setting(SettingIdentifier.SETTINGS_ENABLE_PUSH, 0))
      }
    }

    "respect settings" should {
      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of outgoing streams".inAssertAllStagesStopped(
        new TestSetup(
          Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)) with NetProbes {
          val request = HttpRequest(uri = "https://www.example.com/")
          // server set a very small SETTINGS_MAX_CONCURRENT_STREAMS, so an attempt from the
          // client to open more streams should backpressure
          user.emitRequest(request)
          user.emitRequest(request)
          user.emitRequest(request)
          user.emitRequest(request) // this emit succeeds but is buffered

          // expect frames for 1 3 and 5
          network.expect[HeadersFrame]().streamId shouldBe 1
          network.expect[HeadersFrame]().streamId shouldBe 3
          network.expect[HeadersFrame]().streamId shouldBe 5
          // expect silence on the line
          network.expectNoBytes(100.millis)

          // close 1 and 3
          network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          network.sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          user.emitRequest(request)
          user.emitRequest(request)
          // expect 7 and 9 on the line
          network.expect[HeadersFrame]().streamId shouldBe 7
          network.expect[HeadersFrame]().streamId shouldBe 9
          network.expectNoBytes(100.millis)

          // close 5 7 9
          network.sendFrame(HeadersFrame(streamId = 5, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          network.sendFrame(HeadersFrame(streamId = 7, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          network.sendFrame(HeadersFrame(streamId = 9, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          user.emitRequest(request)
          // expect 11 the line
          network.expect[HeadersFrame]().streamId shouldBe 11
          network.expect[HeadersFrame]().streamId shouldBe 13
        })
      "increasing SETTINGS_MAX_CONCURRENT_STREAMS should flush backpressured outgoing streams".inAssertAllStagesStopped(
        new TestSetup(
          Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 2)) with NetProbes {
          val request = HttpRequest(uri = "https://www.example.com/")
          user.emitRequest(request)
          user.emitRequest(request)
          user.emitRequest(request) // this emit succeeds but is buffered

          // expect frames for 1 and 3
          network.expect[HeadersFrame]().streamId shouldBe 1
          network.expect[HeadersFrame]().streamId shouldBe 3
          // expect silence on the line
          network.expectNoBytes(100.millis)

          // Increasing the capacity...
          network.sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 4)
          network.expectSettingsAck()

          // ... should let frame 5 pass
          network.expect[HeadersFrame]().streamId shouldBe 5
        })
      "decreasing SETTINGS_MAX_CONCURRENT_STREAMS should keep backpressure outgoing streams until limit is respected".inAssertAllStagesStopped(
        new TestSetup(
          Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)) with NetProbes {
          val request = HttpRequest(uri = "https://www.example.com/")
          user.emitRequest(request)
          user.emitRequest(request)
          user.emitRequest(request)
          user.emitRequest(request) // this emit succeeds but is buffered

          // expect frames for 1 3 and 5
          network.expect[HeadersFrame]().streamId shouldBe 1
          network.expect[HeadersFrame]().streamId shouldBe 3
          network.expect[HeadersFrame]().streamId shouldBe 5
          network.expectNoBytes(100.millis)

          // Decreasing the capacity...
          network.sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 2)
          network.expectSettingsAck()

          network.expectNoBytes(100.millis)

          network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          network.expectNoBytes(100.millis)

          // Once 1 and 3 are closed, there'll be capacity for 7 to go through
          network.sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          network.expect[HeadersFrame]().streamId shouldBe 7
          // .. but not enough capacity for 9
          user.emitRequest(request)
          network.expectNoBytes(100.millis)

        })
    }

    "support streaming for receiving response entity data" should {
      abstract class WaitingForResponseSetup extends TestSetup with NetProbes {
        val streamId = 0x1
        user.emitRequest(Get("/"))
        network.expectDecodedHEADERS(streamId, endStream = true)
      }
      "support trailing headers for responses".inAssertAllStagesStopped(new WaitingForResponseSetup {
        network.sendHEADERS(streamId, endStream = false,
          Seq(
            RawHeader(":status", "200"),
            RawHeader("content-type", "application/octet-stream")))

        val response = user.expectResponse()
        response.entity shouldBe a[Chunked]

        network.sendDATA(streamId, endStream = false, ByteString("asdf"))
        network.sendHEADERS(streamId, endStream = true, Seq(RawHeader("grpc-status", "0")))

        val chunks = response.entity.asInstanceOf[Chunked].chunks.runWith(Sink.seq).futureValue
        chunks(0) should be(Chunk("asdf"))
        chunks(1) should be(LastChunk(extension = "", List(RawHeader("grpc-status", "0"))))
      })

    }

    "expose synthetic headers" should {
      "expose Tls-Session-Info".inAssertAllStagesStopped(new TestSetup {
        lazy val expectedSession = SSLContext.getDefault.createSSLEngine.getSession

        override def settings: ClientConnectionSettings =
          super.settings.withParserSettings(
            super.settings.parserSettings
              .withIncludeTlsSessionInfoHeader(true)
              // let's sneak in test coverage of the attribute here as well as it requires the same setup
              .withIncludeSslSessionAttribute(true))

        override def modifyClient(client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed]) =
          BidiFlow.fromGraph(client.withAttributes(
            HttpAttributes.tlsSessionInfo(expectedSession)))

        val streamId = 0x1
        user.emitRequest(Get("/"))
        network.expectDecodedHEADERS(streamId, endStream = true)

        network.sendHEADERS(streamId, endStream = false,
          Seq(
            RawHeader(":status", "200"),
            RawHeader("content-type", "application/octet-stream")))

        val response = user.expectResponse()
        val tlsSessionInfoHeader = response.header[headers.`Tls-Session-Info`].get
        tlsSessionInfoHeader.session shouldBe expectedSession
        response.attribute(AttributeKeys.sslSession).get.session shouldBe expectedSession
      })
    }

    "support for configurable ping" should {
      "send pings when there is an active but slow stream from client".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          override def settings = super.settings.mapHttp2Settings(_.withPingInterval(500.millis))
          val streamId = 0x1
          val requestStream = TestPublisher.probe[ByteString]()
          user.emitRequest(HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(requestStream))))
          network.expectDecodedHEADERS(streamId, endStream = false)
          network.expectNoBytes(250.millis) // no data for 500ms interval should trigger ping (but server counts from emitting last frame, so it's not really 500ms here)
          network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)
          network.expectNoBytes(2.millis) // no data after ping
        })
      "send pings when there is an active but slow stream to client".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          override def settings = {
            val default = super.settings
            default.withHttp2Settings(default.http2Settings.withPingInterval(500.millis))
          }
          val streamId = 0x1
          user.emitRequest(HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`))
          network.expectDecodedHEADERS(streamId)

          network.sendHEADERS(streamId, endStream = false,
            Seq(
              RawHeader(":status", "200"),
              RawHeader("content-type", "application/octet-stream")))
          user.expectResponse()
          network.expectNoBytes(250.millis) // no data for 500ms interval should trigger ping (but server counts from emitting last frame, so it's not really 500ms here)
          network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)
        })

      "send GOAWAY when ping ack times out".inAssertAllStagesStopped(new TestSetup with NetProbes {
        override def settings = {
          val default = super.settings
          default.withHttp2Settings(default.http2Settings.withPingInterval(800.millis).withPingTimeout(400.millis))
        }
        val streamId = 0x1
        val requestStream = TestPublisher.probe[ByteString]()
        user.emitRequest(HttpRequest(
          protocol = HttpProtocols.`HTTP/2.0`,
          entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(requestStream))))
        network.expectDecodedHEADERS(streamId, endStream = false)
        network.expectNoBytes(250.millis) // no data for 800ms interval should trigger ping (but server counts from emitting last frame, so it's not really 800ms here)
        network.expectFrame(FrameType.PING, ByteFlag.Zero, 0, ConfigurablePing.Ping.data)
        network.expectNoBytes(200.millis) // timeout is 400ms second from server emitting ping, (so not really 400ms here)
        val (_, errorCode) = network.expectGOAWAY(streamId)
        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      })
    }

    "delay stage completion" should {

      "until in-flight responses are received".inAssertAllStagesStopped(new TestSetup with NetProbes {
        // Given an in-flight request...
        user.emitRequest(HttpRequest(uri = "https://www.example.com/"))
        network.expectDecodedResponseHEADERSPairs(1)
        // ... and a completed user handler
        user.requestOut.sendComplete()

        network.sendFrame(
          HeadersFrame(streamId = 1, endStream = true, endHeaders = true, HPackSpecExamples.C61FirstResponseWithHuffman,
            None))
        // Then the user can still consume the response
        user.expectResponse()
        user.responseIn.expectComplete()
      })

      "until in-flight streaming responses are received".inAssertAllStagesStopped(new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 3)) with NetProbes {
        // Given several in-flight requests...
        val request = HttpRequest(uri = "https://www.example.com/")
        user.emitRequest(request)
        user.emitRequest(request)
        user.emitRequest(request)
        // ... and backpressured requests...
        user.emitRequest(request) // this emit succeeds but is buffered

        network.expect[HeadersFrame]().streamId shouldBe 1
        network.expect[HeadersFrame]().streamId shouldBe 3
        network.expect[HeadersFrame]().streamId shouldBe 5
        network.expectNoBytes(100.millis)

        // ... and some request already got their response (canary round-trip)
        network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
          HPackSpecExamples.C61FirstResponseWithHuffman, None))
        val resp1: HttpResponse = user.expectResponse()

        // ... and a completed user handler.
        user.requestOut.sendComplete()

        // When the first few response-frames arrive...
        network.sendFrame(HeadersFrame(streamId = 3, endStream = true, endHeaders = true,
          HPackSpecExamples.C61FirstResponseWithHuffman, None))
        // Then the backpressured in-flight streams proceed (sending pending requests)...
        network.expect[HeadersFrame]().streamId shouldBe 7
        network.sendFrame(HeadersFrame(streamId = 5, endStream = false, endHeaders = true,
          HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(DataFrame(streamId = 5, endStream = true, ByteString("Hello World 5!")))
        network.sendFrame(HeadersFrame(streamId = 7, endStream = false, endHeaders = true,
          HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(DataFrame(streamId = 7, endStream = true, ByteString("Hello World 7!")))
        // ... and it is possible to consume all responses (from stream 1 to stream 7)
        val resp3: HttpResponse = user.expectResponse()
        val resp5: HttpResponse = user.expectResponse()
        val resp7: HttpResponse = user.expectResponse()

        private val eventualEntities: Seq[Future[HttpEntity.Strict]] =
          Seq(resp1, resp3, resp5, resp7).map(_.entity.toStrict(100.millis))
        private val entities: Seq[HttpEntity.Strict] = Future.sequence(eventualEntities).futureValue
        // The complete entity of the responses is available
        entities shouldBe Seq(
          HttpEntity.Empty, // 1 has no data
          HttpEntity.Empty, // 3 has no data
          HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("Hello World 5!")),
          HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("Hello World 7!")))

        // Completion doesn't happen until entities are consumed
        user.responseIn.expectComplete()
      })

      "until in-flight streaming requests are fully sent (after response is already complete) (uncommon)".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          val requestStreamProbe = TestPublisher.probe[ByteString]()
          val requestEntity =
            HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(requestStreamProbe))

          // Given several in-flight requests...
          val request = HttpRequest(method = HttpMethods.POST, uri = "https://www.example.com/", entity = requestEntity)
          user.emitRequest(request)

          network.expectDecodedResponseHEADERSPairs(1, endStream = false)

          // ... and some request already got their response (canary round-trip)
          network.sendFrame(HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
            HPackSpecExamples.C61FirstResponseWithHuffman, None))
          user.expectResponse()

          requestStreamProbe.sendNext(ByteString("hello"))
          network.expectDATA(1, endStream = false, ByteString("hello"))

          // now closing user out
          user.requestOut.sendComplete()

          // stage needs to stay alive to send rest of request entity
          requestStreamProbe.sendNext(ByteString("world"))
          network.expectDATA(1, endStream = false, ByteString("world"))

          // queueing more data in the multiplexer without immediately fetching it on the network
          requestStreamProbe.sendNext(ByteString("almost"))
          requestStreamProbe.sendNext(ByteString(" done"))
          requestStreamProbe.sendComplete()
          network.expectDATA(1, endStream = true, ByteString("almost done"))

          // Completion doesn't happen until entities are sent
          user.responseIn.expectComplete()
          network.toNet.expectComplete()
          network.fromNet.expectCancellation()
        })

      "until a timeout occurs".inAssertAllStagesStopped(new TestSetup(
        Setting(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 2)) with NetProbes {
        // Given several in-flight requests...
        val request = HttpRequest(uri = "https://www.example.com/")
        user.emitRequest(request)
        user.emitRequest(request)
        network.expect[HeadersFrame]().streamId shouldBe 1
        network.expect[HeadersFrame]().streamId shouldBe 3
        // got a complete response for 1 but but not for 3
        network.sendFrame(HeadersFrame(streamId = 1, endStream = false, endHeaders = true,
          HPackSpecExamples.C61FirstResponseWithHuffman, None))
        network.sendFrame(DataFrame(streamId = 1, endStream = true, ByteString("Hello World 1!")))
        user.expectResponse()

        // Then the user handler completes
        user.requestOut.sendComplete()

        // The streams is on hold until stream '3' finishes...
        // 400 millis is slightly less than the value of "pekko.http.client.http2.completion-timeout"
        user.responseIn.expectNoMessage(400.millis)

        // Eventually, completion happens after a timeout
        user.responseIn.expectComplete()
      })

      "actually complete as soon as required if there are no open (in-flight) streams".inAssertAllStagesStopped(
        new TestSetup with NetProbes {
          // Given a request...
          user.emitRequest(HttpRequest(uri = "https://www.example.com/"))
          network.expectDecodedResponseHEADERSPairs(1)
          // ... and return response for that request (so there's nothing in-flight)
          network.sendFrame(
            HeadersFrame(streamId = 1, endStream = true, endHeaders = true,
              HPackSpecExamples.C61FirstResponseWithHuffman, None))
          user.expectResponse()

          // When the user completes the stream
          user.requestOut.sendComplete()

          // Then all stages are stopped
          user.responseIn.expectComplete()
        })

    }

  }

  implicit class InWithStoppedStages(name: String) {
    def inAssertAllStagesStopped(runTest: => TestSetup) =
      name in StreamTestKit.assertAllStagesStopped {
        val setup = runTest

        // force connection to shutdown (in case it is an invalid state)
        setup.network.fromNet.sendError(new RuntimeException)
        setup.network.toNet.cancel()

        // and then assert that all stages, substreams in particular, are stopped
      }
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    private lazy val responseIn = TestSubscriber.probe[HttpResponse]()
    private lazy val requestOut = TestPublisher.probe[HttpRequest]()

    def netFlow: Flow[ByteString, ByteString, NotUsed]

    // hook to modify client, for example to add attributes
    def modifyClient(client: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed]) = client

    // hook to modify client settings
    def settings = ClientConnectionSettings(system)

    final def theClient: BidiFlow[ByteString, HttpResponse, HttpRequest, ByteString, NotUsed] =
      modifyClient(Http2Blueprint.clientStack(settings, system.log, NoOpTelemetry))
        .atop(LogByteStringTools.logByteStringBidi("network-plain-text").addAttributes(
          Attributes(LogLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))))
        .reversed

    netFlow
      .join(theClient)
      .join(Flow.fromSinkAndSource(Sink.fromSubscriber(responseIn), Source.fromPublisher(requestOut)))
      .withAttributes(Attributes.inputBuffer(1, 1))
      .run()

    lazy val user = new UserSide(requestOut, responseIn)
  }

  class UserSide(val requestOut: TestPublisher.Probe[HttpRequest], val responseIn: TestSubscriber.Probe[HttpResponse]) {
    def expectResponse(): HttpResponse = responseIn.requestNext()
    def emitRequest(request: HttpRequest): Unit = requestOut.sendNext(request)
  }

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup(initialServerSettings: Setting*) extends TestSetupWithoutHandshake with NetProbes {
    network.toNet.expectBytes(Http2Protocol.ClientConnectionPreface)
    network.expectSETTINGS()

    network.sendFrame(SettingsFrame(immutable.Seq.empty ++ initialServerSettings))
    network.expectSettingsAck()

    def connectionShouldStillBeUsable(): Unit = {
      user.emitRequest(Get("/"))
      val streamId = network.expect[HeadersFrame]().streamId
      network.sendHEADERS(streamId, endStream = true, Seq(RawHeader(":status", "418")))
      user.expectResponse().status should be(StatusCodes.ImATeapot)
    }
  }

  /** Provides the net flow as `toNet` and `fromNet` probes for manual stream interaction */
  trait NetProbes extends TestSetupWithoutHandshake {
    private lazy val framesOut: Http2FrameProbe = Http2FrameProbe()
    private lazy val toNet: ByteStringSinkProbe = framesOut.plainDataProbe
    private lazy val fromNet: TestPublisher.Probe[ByteString] = TestPublisher.probe[ByteString]()

    override def netFlow: Flow[ByteString, ByteString, NotUsed] =
      Flow.fromSinkAndSource(toNet.sink, Source.fromPublisher(fromNet))

    def expectGracefulCompletion(): Unit = {
      user.responseIn.expectComplete()
      toNet.expectComplete()
    }

    lazy val network = new NetworkSide(fromNet, toNet, framesOut)
  }
  class NetworkSide(val fromNet: TestPublisher.Probe[ByteString], val toNet: ByteStringSinkProbe,
      val framesOut: Http2FrameProbe) extends WindowTracking with Http2FrameHpackSupport {
    override def frameProbeDelegate: Http2FrameProbe = framesOut

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)
  }
}
