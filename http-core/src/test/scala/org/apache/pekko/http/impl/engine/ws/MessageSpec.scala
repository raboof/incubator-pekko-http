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

package org.apache.pekko.http.impl.engine.ws

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko
import pekko.NotUsed

import scala.concurrent.duration._
import scala.util.{ Failure, Random }
import pekko.stream.scaladsl._
import pekko.stream.testkit._
import pekko.util.ByteString
import pekko.http.scaladsl.model.ws._
import Protocol.Opcode
import pekko.http.impl.settings.WebSocketSettingsImpl
import pekko.http.impl.util.{ LogByteStringTools, PekkoSpecWithMaterializer }
import pekko.http.scaladsl.settings.WebSocketSettings
import pekko.testkit._
import pekko.stream.OverflowStrategy
import org.scalatest.concurrent.Eventually

import scala.concurrent.Await

class MessageSpec extends PekkoSpecWithMaterializer(
      """
     pekko.http.server.websocket.log-frames = on
     pekko.http.client.websocket.log-frames = on
  """) with Eventually {

  import WSTestUtils._

  val InvalidUtf8TwoByteSequence: ByteString = ByteString(
    (128 + 64).toByte, // start two byte sequence
    0 // but don't finish it
  )

  "The WebSocket implementation should" should {
    "collect messages from frames" should {
      "for binary messages" should {
        "for an empty message" in new ClientTestSetup {
          val input = frameHeader(Opcode.Binary, 0, fin = true)

          pushInput(input)
          expectMessage(BinaryMessage.Strict(ByteString.empty))
        }
        "for one complete, strict, single frame message" in new ClientTestSetup {
          val data = ByteString("abcdef", "ASCII")
          val input = frameHeader(Opcode.Binary, 6, fin = true) ++ data

          pushInput(input)
          expectMessage(BinaryMessage.Strict(data))
        }
        "for a partial frame" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header = frameHeader(Opcode.Binary, 6, fin = true)

          pushInput(header ++ data1)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)
        }
        "for a frame split up into parts" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header = frameHeader(Opcode.Binary, 6, fin = true)

          pushInput(header)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          pushInput(data1)
          sub.expectNext(data1)

          val data2 = ByteString("def", "ASCII")
          pushInput(data2)
          sub.expectNext(data2)
          s.request(1)
          sub.expectComplete()
        }

        "for a message split into several frames" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header1 = frameHeader(Opcode.Binary, 3, fin = false)

          pushInput(header1 ++ data1)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)

          val header2 = frameHeader(Opcode.Continuation, 4, fin = true)
          val data2 = ByteString("defg", "ASCII")
          pushInput(header2 ++ data2)
          sub.expectNext(data2)
          s.request(1)
          sub.expectComplete()
        }
        "for several messages" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header1 = frameHeader(Opcode.Binary, 3, fin = false)

          pushInput(header1 ++ data1)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)

          val header2 = frameHeader(Opcode.Continuation, 4, fin = true)
          val header3 = frameHeader(Opcode.Binary, 2, fin = true)
          val data2 = ByteString("defg", "ASCII")
          val data3 = ByteString("h")
          pushInput(header2 ++ data2 ++ header3 ++ data3)
          sub.expectNext(data2)
          s.request(1)
          sub.expectComplete()

          val dataSource2 = expectBinaryMessage().dataStream
          val sub2 = TestSubscriber.manualProbe[ByteString]()
          dataSource2.runWith(Sink.fromSubscriber(sub2))
          val s2 = sub2.expectSubscription()
          s2.request(2)
          sub2.expectNext(data3)

          val data4 = ByteString("i")
          pushInput(data4)
          sub2.expectNext(data4)
          s2.request(1)
          sub2.expectComplete()
        }
        "unmask masked input on the server side" in new ServerTestSetup {
          val mask = Random.nextInt()
          val (data, _) = maskedASCII("abcdef", mask)
          val data1 = data.take(3)
          val data2 = data.drop(3)
          val header = frameHeader(Opcode.Binary, 6, fin = true, mask = Some(mask))

          pushInput(header ++ data1)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(ByteString("abc", "ASCII"))

          pushInput(data2)
          sub.expectNext(ByteString("def", "ASCII"))
          s.request(1)
          sub.expectComplete()
        }
        "unmask masked input on the server side for empty frame" in new ServerTestSetup {
          val mask = Random.nextInt()
          val header = frameHeader(Opcode.Binary, 0, fin = true, mask = Some(mask))

          pushInput(header)
          expectBinaryMessage(BinaryMessage.Strict(ByteString.empty))
        }
      }
      "for text messages" should {
        "empty message" in new ClientTestSetup {
          val input = frameHeader(Opcode.Text, 0, fin = true)

          pushInput(input)
          expectMessage(TextMessage.Strict(""))
        }
        "decode complete, strict frame from utf8" in new ClientTestSetup {
          val msg = "äbcdef€\uffff"
          val data = ByteString(msg, "UTF-8")
          val input = frameHeader(Opcode.Text, data.size, fin = true) ++ data

          pushInput(input)
          expectMessage(TextMessage.Strict(msg))
        }
        "decode utf8 as far as possible for partial frame" in new ClientTestSetup {
          val msg = "bäcdef€"
          val data = ByteString(msg, "UTF-8")
          val data0 = data.slice(0, 2)
          val data1 = data.slice(2, 5)
          val input = frameHeader(Opcode.Text, data.size, fin = true) ++ data0

          pushInput(input)
          val parts = expectTextMessage().textStream
          val sub = TestSubscriber.manualProbe[String]()
          parts.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(4)
          sub.expectNext("b")

          pushInput(data1)
          sub.expectNext("äcd")
        }
        "decode utf8 with code point split across frames" in new ClientTestSetup {
          val msg = "äbcdef€"
          val data = ByteString(msg, "UTF-8")
          val data0 = data.slice(0, 1)
          val data1 = data.slice(1, data.size)
          val header0 = frameHeader(Opcode.Text, data0.size, fin = false)

          pushInput(header0 ++ data0)
          val parts = expectTextMessage().textStream
          val sub = TestSubscriber.manualProbe[String]()
          parts.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(4)
          sub.expectNoMessage(100.millis)

          val header1 = frameHeader(Opcode.Continuation, data1.size, fin = true)
          pushInput(header1 ++ data1)
          sub.expectNext("äbcdef€")
        }
        "unmask masked input on the server side" in new ServerTestSetup {
          val mask = Random.nextInt()
          val (data, _) = maskedUTF8("äbcdef€", mask)
          val data1 = data.take(3)
          val data2 = data.drop(3)
          val header = frameHeader(Opcode.Binary, data.size, fin = true, mask = Some(mask))

          pushInput(header ++ data1)
          val dataSource = expectBinaryMessage().dataStream
          val sub = TestSubscriber.manualProbe[ByteString]()
          dataSource.runWith(Sink.fromSubscriber(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(ByteString("äb", "UTF-8"))

          pushInput(data2)
          sub.expectNext(ByteString("cdef€", "UTF-8"))
          s.request(1)
          sub.expectComplete()
        }
        "unmask masked input on the server side for empty frame" in new ServerTestSetup {
          val mask = Random.nextInt()
          val header = frameHeader(Opcode.Text, 0, fin = true, mask = Some(mask))

          pushInput(header)
          expectTextMessage(TextMessage.Strict(""))
        }
      }
      "apply backpressure to the network if a message isn't read by the user" in new ServerTestSetup {
        val mask = Random.nextInt()
        val header = frameHeader(Opcode.Text, 65535, fin = false, mask = Some(mask))
        val singleByte = ByteString("a")

        pushInput(header)

        // push single-byte ByteStrings without reading anything until it fails
        // this should be after the internal input buffers have filled up
        eventually(timeout(1.second.dilated), interval(10.millis)) {
          // netIn.within is needed to avoid the default dilated timeout in `pushInput`.
          // This will make the eventually loop run fast enough to actually
          // fill buffers and fail within the timeout.
          an[AssertionError] should be thrownBy netIn.within(10.millis)(pushInput(singleByte))
        }
      }
    }
    "render frames from messages" should {
      "for binary messages" should {
        "for a short strict message" in new ServerTestSetup {
          val data = ByteString("abcdef", "ASCII")
          val msg = BinaryMessage.Strict(data)
          pushMessage(msg)

          expectFrameOnNetwork(Opcode.Binary, data, fin = true)
        }
        "for a strict message larger than configured maximum frame size" in pending
        "for a streamed message" in new ServerTestSetup {
          val data = ByteString("abcdefg", "ASCII")
          val pub = TestPublisher.manualProbe[ByteString]()
          val msg = BinaryMessage(Source.fromPublisher(pub))
          pushMessage(msg)
          val sub = pub.expectSubscription()

          val data1 = data.take(3)
          val data2 = data.drop(3)

          sub.sendNext(data1)
          expectFrameOnNetwork(Opcode.Binary, data1, fin = false)

          sub.sendNext(data2)
          expectFrameOnNetwork(Opcode.Continuation, data2, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "for a streamed message with a chunk being larger than configured maximum frame size" in pending
        "and mask input on the client side" in new ClientTestSetup {
          val data = ByteString("abcdefg", "ASCII")
          val pub = TestPublisher.manualProbe[ByteString]()
          val msg = BinaryMessage(Source.fromPublisher(pub))
          pushMessage(msg)
          val sub = pub.expectSubscription()

          val data1 = data.take(3)
          val data2 = data.drop(3)

          sub.sendNext(data1)
          expectMaskedFrameOnNetwork(Opcode.Binary, data1, fin = false)

          sub.sendNext(data2)
          expectMaskedFrameOnNetwork(Opcode.Continuation, data2, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "and mask input on the client side for empty frame" in new ClientTestSetup {
          pushMessage(BinaryMessage(ByteString.empty))
          expectMaskedFrameOnNetwork(Opcode.Binary, ByteString.empty, fin = true)
        }
      }
      "for text messages" should {
        "for a short strict message" in new ServerTestSetup {
          val text = "äbcdef"
          val msg = TextMessage.Strict(text)
          pushMessage(msg)

          expectFrameOnNetwork(Opcode.Text, ByteString(text, "UTF-8"), fin = true)
        }
        "for a strict message larger than configured maximum frame size" in pending
        "for a streamed message" in new ServerTestSetup {
          val text = "äbcd€fg"
          val pub = TestPublisher.manualProbe[String]()
          val msg = TextMessage(Source.fromPublisher(pub))
          pushMessage(msg)
          val sub = pub.expectSubscription()

          val text1 = text.take(3)
          val text1Bytes = ByteString(text1, "UTF-8")
          val text2 = text.drop(3)
          val text2Bytes = ByteString(text2, "UTF-8")

          sub.sendNext(text1)
          expectFrameOnNetwork(Opcode.Text, text1Bytes, fin = false)

          sub.sendNext(text2)
          expectFrameOnNetwork(Opcode.Continuation, text2Bytes, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "for a streamed message don't convert half surrogate pairs naively" in new ServerTestSetup {
          val gclef = "𝄞"
          gclef.length shouldEqual 2

          // split up the code point
          val half1 = gclef.take(1)
          val half2 = gclef.drop(1)

          val pub = TestPublisher.manualProbe[String]()
          val msg = TextMessage(Source.fromPublisher(pub))

          pushMessage(msg)
          val sub = pub.expectSubscription()

          sub.sendNext(half1)

          expectNoNetworkData()
          sub.sendNext(half2)
          expectFrameOnNetwork(Opcode.Text, ByteString(gclef, "utf8"), fin = false)
        }
        "for a streamed message with a chunk being larger than configured maximum frame size" in pending
        "and mask input on the client side" in new ClientTestSetup {
          val text = "abcdefg"
          val pub = TestPublisher.manualProbe[String]()
          val msg = TextMessage(Source.fromPublisher(pub))
          pushMessage(msg)
          val sub = pub.expectSubscription()

          val text1 = text.take(3)
          val text1Bytes = ByteString(text1, "UTF-8")
          val text2 = text.drop(3)
          val text2Bytes = ByteString(text2, "UTF-8")

          sub.sendNext(text1)
          expectMaskedFrameOnNetwork(Opcode.Text, text1Bytes, fin = false)

          sub.sendNext(text2)
          expectMaskedFrameOnNetwork(Opcode.Continuation, text2Bytes, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "and mask input on the client side for empty frame" in new ClientTestSetup {
          pushMessage(TextMessage(""))
          expectMaskedFrameOnNetwork(Opcode.Text, ByteString.empty, fin = true)
        }
      }
    }
    "supply automatic low-level websocket behavior" should {
      "respond to ping frames unmasking them on the server side" in new ServerTestSetup {
        val mask = Random.nextInt()
        val input = frameHeader(Opcode.Ping, 6, fin = true, mask = Some(mask)) ++ maskedASCII("abcdef", mask)._1

        pushInput(input)
        expectFrameOnNetwork(Opcode.Pong, ByteString("abcdef"), fin = true)
      }
      "respond to ping frames masking them on the client side" in new ClientTestSetup {
        val input = frameHeader(Opcode.Ping, 6, fin = true) ++ ByteString("abcdef")

        pushInput(input)
        expectMaskedFrameOnNetwork(Opcode.Pong, ByteString("abcdef"), fin = true)
      }
      "respond to ping frames interleaved with data frames (without mixing frame data)" in new ServerTestSetup {
        // receive multi-frame message
        // receive and handle interleaved ping frame
        // concurrently send out messages from handler
        val mask1 = Random.nextInt()
        val input1 = frameHeader(Opcode.Binary, 3, fin = false, mask = Some(mask1)) ++ maskedASCII("123", mask1)._1
        pushInput(input1)

        val dataSource = expectBinaryMessage().dataStream
        val sub = TestSubscriber.manualProbe[ByteString]()
        dataSource.runWith(Sink.fromSubscriber(sub))
        val s = sub.expectSubscription()
        s.request(2)
        sub.expectNext(ByteString("123", "ASCII"))

        val outPub = TestPublisher.manualProbe[ByteString]()
        val msg = BinaryMessage(Source.fromPublisher(outPub))
        pushMessage(msg)

        val outSub = outPub.expectSubscription()
        val outData1 = ByteString("abc", "ASCII")
        outSub.sendNext(outData1)
        expectFrameOnNetwork(Opcode.Binary, outData1, fin = false)

        val pingMask = Random.nextInt()
        val pingData = maskedASCII("pling", pingMask)._1
        val pingData0 = pingData.take(3)
        val pingData1 = pingData.drop(3)
        pushInput(frameHeader(Opcode.Ping, 5, fin = true, mask = Some(pingMask)) ++ pingData0)
        expectNoNetworkData()
        pushInput(pingData1)
        expectFrameOnNetwork(Opcode.Pong, ByteString("pling", "ASCII"), fin = true)

        val outData2 = ByteString("def", "ASCII")
        outSub.sendNext(outData2)
        expectFrameOnNetwork(Opcode.Continuation, outData2, fin = false)

        outSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

        val mask2 = Random.nextInt()
        val input2 = frameHeader(Opcode.Continuation, 3, fin = true, mask = Some(mask2)) ++ maskedASCII("456", mask2)._1
        pushInput(input2)
        sub.expectNext(ByteString("456", "ASCII"))
        s.request(1)
        sub.expectComplete()
      }
      "don't respond to unsolicited pong frames" in new ClientTestSetup {
        val data = frameHeader(Opcode.Pong, 6, fin = true) ++ ByteString("abcdef")
        pushInput(data)
        expectNoNetworkData()
      }

      List("ping", "pong").foreach { keepAliveMode =>
        def expectedOpcode = if (keepAliveMode == "ping") Opcode.Ping else Opcode.Pong

        s"automatically send keep-alive [$keepAliveMode] frames, with empty data, when configured on the server side" in new ServerTestSetup {
          override def websocketSettings: WebSocketSettings = // configures server to do keep-alive
            super.websocketSettings
              .withPeriodicKeepAliveMode(keepAliveMode)
              .withPeriodicKeepAliveMaxIdle(100.millis)

          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
        }
        s"automatically send keep-alive [$keepAliveMode] frames, with data payload, when configured on the server side" in new ServerTestSetup {
          val counter = new AtomicInteger()
          override def websocketSettings: WebSocketSettings = // configures server to do keep-alive
            super.websocketSettings
              .withPeriodicKeepAliveMode(keepAliveMode)
              .withPeriodicKeepAliveMaxIdle(100.millis)
              .withPeriodicKeepAliveData(() => ByteString(s"ping-${counter.incrementAndGet()}"))

          expectFrameOnNetwork(expectedOpcode, ByteString("ping-1"), fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString("ping-2"), fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString("ping-3"), fin = true)
        }

        s"automatically send keep-alive [$keepAliveMode] frames, with empty data, when configured on the client side" in new ClientTestSetup {
          override def websocketSettings: WebSocketSettings = // configures client to do keep-alive
            super.websocketSettings
              .withPeriodicKeepAliveMode(keepAliveMode)
              .withPeriodicKeepAliveMaxIdle(100.millis)

          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
          expectFrameOnNetwork(expectedOpcode, ByteString.empty, fin = true)
        }
        s"automatically send keep-alive [$keepAliveMode] frames, with data payload, when configured on the client side" in new ClientTestSetup {
          val counter = new AtomicInteger()
          override def websocketSettings: WebSocketSettings = // configures client to do keep-alive
            super.websocketSettings
              .withPeriodicKeepAliveMode(keepAliveMode)
              .withPeriodicKeepAliveMaxIdle(100.millis)
              .withPeriodicKeepAliveData(() => ByteString(s"ping-${counter.incrementAndGet()}"))

          expectMaskedFrameOnNetwork(expectedOpcode, ByteString("ping-1"), fin = true)
          expectMaskedFrameOnNetwork(expectedOpcode, ByteString("ping-2"), fin = true)
          expectMaskedFrameOnNetwork(expectedOpcode, ByteString("ping-3"), fin = true)
        }
      }
    }
    "provide close behavior" should {
      "after receiving regular close frame when idle (user closes immediately)" in new ServerTestSetup {
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))
        expectComplete(messageIn)

        netIn.expectNoMessage(100.millis) // especially the cancellation not yet
        expectNoNetworkData()
        messageOut.sendComplete()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "after receiving close frame without close code" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Close, 0, fin = true, mask = Some(Random.nextInt())))
        expectComplete(messageIn)

        messageOut.sendComplete()
        // especially mustn't be Protocol.CloseCodes.NoCodePresent
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "after receiving regular close frame when idle (but some data was exchanged before)" in new ServerTestSetup {
        val msg = "äbcdef€\uffff"
        val input = frame(Opcode.Text, ByteString(msg, "UTF-8"), fin = true, mask = true)

        // send at least one regular frame to trigger #19340 afterwards
        pushInput(input)
        expectMessage(TextMessage.Strict(msg))

        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))
        expectComplete(messageIn)

        netIn.expectNoMessage(100.millis) // especially the cancellation not yet
        expectNoNetworkData()
        messageOut.sendComplete()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "after receiving regular close frame when idle (user still sends some data)" in new ServerTestSetup {
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))
        expectComplete(messageIn)

        // sending another message is allowed before closing (inherently racy)
        val pub = TestPublisher.manualProbe[ByteString]()
        val msg = BinaryMessage(Source.fromPublisher(pub))
        pushMessage(msg)

        val data = ByteString("abc", "ASCII")
        val dataSub = pub.expectSubscription()
        dataSub.sendNext(data)
        expectFrameOnNetwork(Opcode.Binary, data, fin = false)

        dataSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

        messageOut.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
      }
      "after receiving regular close frame when fragmented message is still open" in new ServerTestSetup {
        pushInput(frameHeader(Protocol.Opcode.Binary, 0, fin = false, mask = Some(Random.nextInt())))
        val dataSource = expectBinaryMessage().dataStream
        val inSubscriber = TestSubscriber.manualProbe[ByteString]()
        dataSource.runWith(Sink.fromSubscriber(inSubscriber))
        val inSub = inSubscriber.expectSubscription()

        val outData = ByteString("def", "ASCII")
        val mask = Random.nextInt()
        pushInput(frameHeader(Protocol.Opcode.Continuation, 3, fin = false, mask = Some(mask)) ++ maskedBytes(outData,
          mask)._1)
        inSub.request(5)
        inSubscriber.expectNext(outData)

        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        // This is arguable: we could also just fail the subStream but complete the main message stream regularly.
        // However, truncating an ongoing message by closing without sending a `Continuation(fin = true)` first
        // could be seen as something being amiss.
        expectError(messageIn)
        inSubscriber.expectError()
        // truncation of open message

        // sending another message is allowed before closing (inherently racy)

        val pub = TestPublisher.manualProbe[ByteString]()
        val msg = BinaryMessage(Source.fromPublisher(pub))
        pushMessage(msg)

        val data = ByteString("abc", "ASCII")
        val dataSub = pub.expectSubscription()
        dataSub.sendNext(data)
        expectFrameOnNetwork(Opcode.Binary, data, fin = false)

        dataSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

        messageOut.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()

      }
      "after receiving error close frame with close code and without reason" in new ServerTestSetup {
        pushInput(closeFrame(Protocol.CloseCodes.UnexpectedCondition, mask = true))
        val error = expectError(messageIn).asInstanceOf[PeerClosedConnectionException]
        error.closeCode shouldEqual Protocol.CloseCodes.UnexpectedCondition
        error.closeReason shouldEqual ""

        expectCloseCodeOnNetwork(Protocol.CloseCodes.UnexpectedCondition)
        messageOut.sendError(error)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "after receiving error close frame with close code and with reason" in new ServerTestSetup {
        pushInput(closeFrame(Protocol.CloseCodes.UnexpectedCondition, mask = true,
          msg = "This alien landing came quite unexpected. Communication has been garbled."))
        val error = expectError(messageIn).asInstanceOf[PeerClosedConnectionException]
        error.closeCode shouldEqual Protocol.CloseCodes.UnexpectedCondition
        error.closeReason shouldEqual "This alien landing came quite unexpected. Communication has been garbled."

        expectCloseCodeOnNetwork(Protocol.CloseCodes.UnexpectedCondition)
        messageOut.sendError(error)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "after peer closes connection without sending a close frame" in new ServerTestSetup {
        netIn.expectRequest()
        netIn.sendComplete()

        expectComplete(messageIn)
        messageOut.sendComplete()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
      }
      "when user handler closes (simple)" in new ServerTestSetup {
        messageOut.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

        expectNoNetworkData() // wait for peer to close regularly
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        expectComplete(messageIn)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "when user handler closes main stream and substream only afterwards" in new ServerTestSetup {
        // send half a message
        val pub = TestPublisher.manualProbe[ByteString]()
        val msg = BinaryMessage(Source.fromPublisher(pub))
        pushMessage(msg)

        val data = ByteString("abc", "ASCII")
        val dataSub = pub.expectSubscription()
        dataSub.sendNext(data)
        expectFrameOnNetwork(Opcode.Binary, data, fin = false)

        messageOut.sendComplete()
        expectNoNetworkData() // need to wait for substream to close

        dataSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        expectNoNetworkData() // wait for peer to close regularly

        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        expectComplete(messageIn)
        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "if user handler fails" in new ServerTestSetup {
        EventFilter[RuntimeException](message = "Oops, user handler failed!", occurrences = 1)
          .intercept {
            messageOut.sendError(new RuntimeException("Oops, user handler failed!"))
            expectCloseCodeOnNetwork(Protocol.CloseCodes.UnexpectedCondition)

            expectNoNetworkData() // wait for peer to close regularly
            pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

            expectComplete(messageIn)
            netOut.expectComplete()
            netIn.expectCancellation()
          }
      }
      "if peer closes with invalid close frame" should {
        "close code outside of the valid range" in new ServerTestSetup {
          pushInput(closeFrame(5700, mask = true))

          val error = expectError(messageIn).asInstanceOf[PeerClosedConnectionException]
          error.closeCode shouldEqual Protocol.CloseCodes.ProtocolError
          error.closeReason shouldEqual "Peer sent illegal close frame (invalid close code '5700')."

          expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
          netOut.expectComplete()
          netIn.expectCancellation()
        }
        "close data of size 1" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Close, 1, mask = Some(Random.nextInt()), fin = true) ++ ByteString("x"))

          val error = expectError(messageIn).asInstanceOf[PeerClosedConnectionException]
          error.closeCode shouldEqual Protocol.CloseCodes.ProtocolError
          error.closeReason shouldEqual "Peer sent illegal close frame (close code must be length 2 but was 1)."

          expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
          netOut.expectComplete()
          netIn.expectCancellation()
        }
        "close message is invalid UTF8" in new ServerTestSetup {
          pushInput(closeFrame(Protocol.CloseCodes.UnexpectedCondition, mask = true,
            msgBytes = InvalidUtf8TwoByteSequence))

          val error = expectError(messageIn).asInstanceOf[PeerClosedConnectionException]
          error.closeCode shouldEqual Protocol.CloseCodes.ProtocolError
          error.closeReason shouldEqual "Peer sent illegal close frame (close reason message is invalid UTF8)."

          expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
          netOut.expectComplete()
          netIn.expectCancellation()
        }
      }
      "timeout if user handler closes and peer doesn't send a close frame" in new ServerTestSetup {
        override protected def closeTimeout: FiniteDuration = 100.millis.dilated

        EventFilter.debug(pattern = "Peer did not acknowledge CLOSE frame after .*, closing underlying connection now.",
          occurrences = 1).intercept {
          messageOut.sendComplete()
          expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

          netOut.expectComplete()
          netIn.expectCancellation()
        }
      }
      "timeout after we close after error and peer doesn't send a close frame" in new ServerTestSetup {
        override protected def closeTimeout: FiniteDuration = 100.millis.dilated

        pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv1 = true))
        expectProtocolErrorOnNetwork()
        messageOut.sendComplete()

        netOut.expectComplete()
        netIn.expectCancellation()
      }
      "ignore frames peer sends after close frame" in new ServerTestSetup {
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        expectComplete(messageIn)

        pushInput(frameHeader(Opcode.Binary, 0, fin = true))
        messageOut.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

        netOut.expectComplete()
        netIn.expectCancellation()
      }
    }
    "reject unexpected frames" should {
      "reserved bits set" should {
        "rsv1" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv1 = true))
          expectProtocolErrorOnNetwork()
        }
        "rsv2" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv2 = true))
          expectProtocolErrorOnNetwork()
        }
        "rsv3" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv3 = true))
          expectProtocolErrorOnNetwork()
        }
      }
      "highest bit of 64-bit length is set" in new ServerTestSetup {

        import BitBuilder._

        val header =
          b"""0000          # flags
                  xxxx=1    # opcode
              1             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=ffffffff
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=ffffffff # length64
              00000000
              00000000
              00000000
              00000000 # empty mask
          """

        pushInput(header)
        expectProtocolErrorOnNetwork()
      }
      "control frame bigger than 125 bytes" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Ping, 126, fin = true, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "fragmented control frame" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Ping, 0, fin = false, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "unexpected continuation frame" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Continuation, 0, fin = false, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "unexpected data frame when waiting for continuation" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Binary, 0, fin = false) ++
          frameHeader(Opcode.Binary, 0, fin = false))
        expectProtocolErrorOnNetwork()
      }
      "invalid utf8 encoding for single frame message" in new ClientTestSetup {
        val data = InvalidUtf8TwoByteSequence

        pushInput(frameHeader(Opcode.Text, 2, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "invalid utf8 encoding for streamed frame" in new ClientTestSetup {
        val data = InvalidUtf8TwoByteSequence

        pushInput(frameHeader(Opcode.Text, 0, fin = false) ++
          frameHeader(Opcode.Continuation, 2, fin = true) ++
          data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "truncated utf8 encoding for single frame message" in new ClientTestSetup {
        val data = ByteString("€", "UTF-8").take(1) // half a euro
        pushInput(frameHeader(Opcode.Text, 1, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "truncated utf8 encoding for streamed frame" in new ClientTestSetup {
        val data = ByteString("€", "UTF-8").take(1) // half a euro
        pushInput(frameHeader(Opcode.Text, 0, fin = false) ++
          frameHeader(Opcode.Continuation, 1, fin = true) ++
          data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "half a surrogate pair in utf8 encoding for a strict frame" in new ClientTestSetup {
        val data = ByteString(0xED, 0xA0, 0x80) // not strictly supported by utf-8
        pushInput(frameHeader(Opcode.Text, 3, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "half a surrogate pair in utf8 encoding for a streamed frame" in new ClientTestSetup {
        val data = ByteString(0xED, 0xA0, 0x80) // not strictly supported by utf-8
        pushInput(frameHeader(Opcode.Text, 0, fin = false))
        pushInput(frameHeader(Opcode.Continuation, 3, fin = true) ++ data)

        // Kids, always drain your entities
        messageIn.requestNext() match {
          case b: TextMessage =>
            b.textStream.runWith(Sink.ignore)
          case _ =>
        }

        expectError(messageIn)

        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "unmasked input on the server side" in new ServerTestSetup {
        val data = ByteString("abcdef", "ASCII")
        val input = frameHeader(Opcode.Binary, 6, fin = true) ++ data

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
      "unmasked input on the server side for empty frame" in new ServerTestSetup {
        val input = frameHeader(Opcode.Binary, 0, fin = true)

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
      "masked input on the client side" in new ClientTestSetup {
        val mask = Random.nextInt()
        val input = frameHeader(Opcode.Binary, 6, fin = true, mask = Some(mask)) ++ maskedASCII("abcdef", mask)._1

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
      "masked input on the client side for empty frame" in new ClientTestSetup {
        val mask = Random.nextInt()
        val input = frameHeader(Opcode.Binary, 0, fin = true, mask = Some(mask))

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
    }
    "convert any message to strict message" should {
      import scala.concurrent.ExecutionContext.Implicits.global

      val msg = "JKS*s';@"
      val pre = "pre"
      val post = "post"
      "convert streamed text with multiple elements to strict text" in {
        Await.result(
          TextMessage
            .Streamed(Source(pre :: msg :: post :: Nil))
            .toStrict(1.second), 1.second) should be(TextMessage.Strict(pre + msg + post))
      }
      "convert streamed binary with multiple elements to strict binary" in {
        Await.result(
          BinaryMessage
            .Streamed(Source(ByteString(pre.getBytes("UTF-8")) :: ByteString(msg.getBytes("UTF-8")) :: ByteString(
              post.getBytes("UTF-8")) :: Nil))
            .toStrict(1.second), 1.second) should be(
          BinaryMessage.Strict(ByteString((pre + msg + post).getBytes("UTF-8"))))
      }
      "convert streamed text to strict text" in {
        Await.result(
          TextMessage
            .Streamed(Source.single(msg))
            .toStrict(1.second), 1.second) should be(TextMessage.Strict(msg))
      }
      "convert streamed binary to strict binary" in {
        Await.result(
          BinaryMessage
            .Streamed(Source.single(ByteString(msg.getBytes("UTF-8"))))
            .toStrict(1.second), 1.second) should be(BinaryMessage.Strict(ByteString(msg.getBytes("UTF-8"))))
      }
      "convert streamed text to strict text if stream is empty" in {
        Await.result(
          TextMessage
            .Streamed(Source.empty)
            .toStrict(1.second), 1.second) should be(TextMessage.Strict(""))
      }
      "convert streamed binary to strict binary if stream is empty" in {
        Await.result(
          BinaryMessage
            .Streamed(Source.empty)
            .toStrict(1.second), 1.second) should be(BinaryMessage.Strict(ByteString()))
      }
      "convert streamed text to strict text if stream is infinite" in {
        val future = Await.ready(
          TextMessage
            .Streamed(Source.repeat(msg))
            .toStrict(1.second), 5.second)

        future.onComplete {
          case Failure(ex) => ex.getClass should be(classOf[TimeoutException])
          case _           => fail()
        }
      }
      "convert streamed binary to strict binary if stream is infinite" in {
        val future = Await.ready(
          BinaryMessage
            .Streamed(Source.repeat(ByteString(msg.getBytes("UTF-8"))))
            .toStrict(1.second), 5.second)

        future.onComplete {
          case Failure(ex) => ex.getClass should be(classOf[TimeoutException])
          case _           => fail()
        }
      }
    }
    "support per-message-compression extension" in pending
  }

  class ServerTestSetup extends TestSetup {
    protected def serverSide: Boolean = true
  }
  class ClientTestSetup extends TestSetup {
    protected def serverSide: Boolean = false
  }
  abstract class TestSetup {
    protected def serverSide: Boolean
    protected def closeTimeout: FiniteDuration = 1.second.dilated
    protected def websocketSettings: WebSocketSettings =
      if (serverSide) WebSocketSettingsImpl.serverFromRoot(system.settings.config)
      else WebSocketSettingsImpl.clientFromRoot(system.settings.config)

    val netIn = TestPublisher.probe[ByteString]()
    val netOut = ByteStringSinkProbe()
    val messageIn = TestSubscriber.probe[Message]()
    val messageOut = TestPublisher.probe[Message]()
    val messageHandler: Flow[Message, Message, NotUsed] =
      Flow.fromSinkAndSource(
        Flow[Message].buffer(1, OverflowStrategy.backpressure).to(Sink.fromSubscriber(messageIn)), // alternatively need to request(1) before expectComplete
        Source.fromPublisher(messageOut))

    def pushInput(data: ByteString): Unit = netIn.sendNext(data)

    def pushMessage(msg: Message): Unit = messageOut.sendNext(msg)

    Source.fromPublisher(netIn)
      .via(LogByteStringTools.logByteString("netIn", 1000))
      .via(FrameEventParser)
      .via(printEvent("stackIn"))
      .via(WebSocket
        .stack(serverSide, websocketSettings = websocketSettings, closeTimeout = closeTimeout, log = system.log)
        .join(messageHandler))
      .via(printEvent("stackOut"))
      .via(new FrameEventRenderer)
      .via(LogByteStringTools.logByteString("netOut", 1000))
      .buffer(1, OverflowStrategy.backpressure) // alternatively need to request(1) before expectComplete
      .to(netOut.sink)
      .run()

    def expectMessage(message: Message): Unit = messageIn.requestNext(message)

    def expectBinaryMessage(message: BinaryMessage): Unit = expectBinaryMessage() shouldEqual message

    def expectBinaryMessage(): BinaryMessage = expectMessage().asInstanceOf[BinaryMessage]

    def expectTextMessage(message: TextMessage): Unit = expectTextMessage() shouldEqual message

    def expectTextMessage(): TextMessage = expectMessage().asInstanceOf[TextMessage]

    def expectMessage(): Message = messageIn.requestNext()

    def expectNetworkData(data: ByteString): Unit = {
      val got = expectNetworkData(data.size)
      got.utf8String shouldEqual data.utf8String
    }

    def expectFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
      expectFrameHeaderOnNetwork(opcode, data.size, fin)
      expectNetworkData(data)
    }

    def expectMaskedFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
      val Some(mask) = expectFrameHeaderOnNetwork(opcode, data.size, fin)
      val masked = maskedBytes(data, mask)._1
      expectNetworkData(masked)
    }

    final def expectNetworkData(bytes: Int): ByteString = netOut.expectBytes(bytes)

    /** Returns the mask if any is available */
    def expectFrameHeaderOnNetwork(opcode: Opcode, length: Long, fin: Boolean): Option[Int] = {
      val (op, l, f, m) = expectFrameHeaderOnNetwork()
      op shouldEqual opcode
      l shouldEqual length
      f shouldEqual fin
      m
    }

    def expectFrameHeaderOnNetwork(): (Opcode, Long, Boolean, Option[Int]) = {
      val header = expectNetworkData(2)

      val fin = (header(0) & Protocol.FIN_MASK) != 0
      val op = header(0) & Protocol.OP_MASK

      val hasMask = (header(1) & Protocol.MASK_MASK) != 0
      val length7 = header(1) & Protocol.LENGTH_MASK
      val length = length7 match {
        case 126 =>
          val length16Bytes = expectNetworkData(2)
          (length16Bytes(0) & 0xFF) << 8 | (length16Bytes(1) & 0xFF) << 0
        case 127 =>
          val length64Bytes = expectNetworkData(8)
          (length64Bytes(0) & 0xFF).toLong << 56 |
          (length64Bytes(1) & 0xFF).toLong << 48 |
          (length64Bytes(2) & 0xFF).toLong << 40 |
          (length64Bytes(3) & 0xFF).toLong << 32 |
          (length64Bytes(4) & 0xFF).toLong << 24 |
          (length64Bytes(5) & 0xFF).toLong << 16 |
          (length64Bytes(6) & 0xFF).toLong << 8 |
          (length64Bytes(7) & 0xFF).toLong << 0
        case x => x
      }
      val mask =
        if (hasMask) {
          val maskBytes = expectNetworkData(4)
          val mask =
            (maskBytes(0) & 0xFF) << 24 |
            (maskBytes(1) & 0xFF) << 16 |
            (maskBytes(2) & 0xFF) << 8 |
            (maskBytes(3) & 0xFF) << 0
          Some(mask)
        } else None

      (Opcode.forCode(op.toByte), length, fin, mask)
    }

    def expectProtocolErrorOnNetwork(): Unit = expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)

    def expectCloseCodeOnNetwork(expectedCode: Int): Unit = {
      val (opcode, length, true, mask) = expectFrameHeaderOnNetwork()
      opcode shouldEqual Opcode.Close
      length should be >= 2.toLong

      val rawData = expectNetworkData(length.toInt)
      val data = mask match {
        case Some(m) => FrameEventParser.mask(rawData, m)._1
        case None    => rawData
      }

      val code = ((data(0) & 0xFF) << 8) | ((data(1) & 0xFF) << 0)
      code shouldEqual expectedCode
    }

    def expectNoNetworkData(): Unit = netOut.expectNoBytes(100.millis.dilated)

    def expectComplete[T](probe: TestSubscriber.Probe[T]): Unit = {
      probe.ensureSubscription()
      probe.request(1)
      probe.expectComplete()
    }

    def expectError[T](probe: TestSubscriber.Probe[T]): Throwable = {
      probe.ensureSubscription()
      probe.request(1)
      probe.expectError()
    }

  }

  def printEvent[T](marker: String): Flow[T, T, NotUsed] = Flow[T].log(marker)
}
