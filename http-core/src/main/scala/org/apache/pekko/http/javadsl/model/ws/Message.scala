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

package org.apache.pekko.http.javadsl.model.ws

import java.util.concurrent.CompletionStage

import org.apache.pekko
import pekko.annotation.DoNotInherit
import pekko.http.scaladsl.{ model => sm }
import pekko.stream.Materializer
import pekko.stream.javadsl.Source
import pekko.util.ByteString

import scala.concurrent.duration._
import scala.compat.java8.FutureConverters._

/**
 * Represents a WebSocket message. A message can either be a binary message or a text message.
 */
@DoNotInherit
abstract class Message {

  /**
   * Is this message a text message? If true, [[asTextMessage]] will return this
   * text message, if false, [[asBinaryMessage]] will return this binary message.
   */
  def isText: Boolean

  /** Is this message a strict one? */
  def isStrict: Boolean

  /**
   * Returns this TextMessage if it is a text message, throws otherwise.
   */
  def asTextMessage: TextMessage

  /**
   * Returns this BinaryMessage if it is a binary message, throws otherwise.
   */
  def asBinaryMessage: BinaryMessage

  def asScala: sm.ws.Message
}

object Message {
  def adapt(msg: sm.ws.Message): Message = msg match {
    case t: sm.ws.TextMessage   => TextMessage.adapt(t)
    case b: sm.ws.BinaryMessage => BinaryMessage.adapt(b)
  }
}

/**
 * Represents a WebSocket text message. A text message can either be strict in which case
 * the complete data is already available or it can be streamed in which case [[getStreamedText]]
 * will return a Source streaming the data as it comes in.
 */
//#message-model
abstract class TextMessage extends Message {

  /**
   * Returns a source of the text message data.
   */
  def getStreamedText: Source[String, _]

  /**
   * Returns the strict message text if this message is strict, throws otherwise.
   */
  def getStrictText: String
  // #message-model
  def isText: Boolean = true
  def asTextMessage: TextMessage = this
  def asBinaryMessage: BinaryMessage = throw new ClassCastException("This message is not a binary message.")
  def asScala: sm.ws.TextMessage
  def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.TextMessage.Strict]
  // #message-model
}
//#message-model

object TextMessage {

  /**
   * Creates a strict text message.
   */
  def create(text: String): TextMessage =
    new TextMessage {
      def isStrict: Boolean = true
      def getStreamedText: Source[String, _] = Source.single(text)
      def getStrictText: String = text

      def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.TextMessage.Strict] = asScala
        .toStrict(timeoutMillis.millis)(materializer)
        .toJava

      def asScala: sm.ws.TextMessage = sm.ws.TextMessage.Strict(text)
    }

  /**
   * Creates a streamed text message.
   */
  def create(textStream: Source[String, _]): TextMessage =
    new TextMessage {
      def isStrict: Boolean = false
      def getStrictText: String = throw new IllegalStateException("Cannot get strict text for streamed message.")
      def getStreamedText: Source[String, _] = textStream

      def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.TextMessage.Strict] = asScala
        .toStrict(timeoutMillis.millis)(materializer)
        .toJava

      def asScala: sm.ws.TextMessage = sm.ws.TextMessage(textStream.asScala)
    }

  def adapt(msg: sm.ws.TextMessage): TextMessage = msg match {
    case sm.ws.TextMessage.Strict(text) => create(text)
    case tm: sm.ws.TextMessage          => create(tm.textStream.asJava)
  }
}

/**
 * Represents a WebSocket binary message. A binary message can either be strict in which case
 * the complete data is already available or it can be streamed in which case [[getStreamedData]]
 * will return a Source streaming the data as it comes in.
 */
abstract class BinaryMessage extends Message {

  /**
   * Returns a source of the binary message data.
   */
  def getStreamedData: Source[ByteString, _]

  /**
   * Returns the strict message data if this message is strict, throws otherwise.
   */
  def getStrictData: ByteString

  def isText: Boolean = false
  def asTextMessage: TextMessage = throw new ClassCastException("This message is not a text message.")
  def asBinaryMessage: BinaryMessage = this
  def asScala: sm.ws.BinaryMessage
  def toStrict(timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.BinaryMessage.Strict]
}

object BinaryMessage {

  /**
   * Creates a strict binary message.
   */
  def create(data: ByteString): BinaryMessage =
    new BinaryMessage {
      def isStrict: Boolean = true
      def getStreamedData: Source[ByteString, _] = Source.single(data)
      def getStrictData: ByteString = data

      def toStrict(
          timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.BinaryMessage.Strict] = asScala
        .toStrict(timeoutMillis.millis)(materializer)
        .toJava

      def asScala: sm.ws.BinaryMessage = sm.ws.BinaryMessage.Strict(data)
    }

  /**
   * Creates a streamed binary message.
   */
  def create(dataStream: Source[ByteString, _]): BinaryMessage =
    new BinaryMessage {
      def isStrict: Boolean = false
      def getStrictData: ByteString = throw new IllegalStateException("Cannot get strict data for streamed message.")
      def getStreamedData: Source[ByteString, _] = dataStream

      def toStrict(
          timeoutMillis: Long, materializer: Materializer): CompletionStage[sm.ws.BinaryMessage.Strict] = asScala
        .toStrict(timeoutMillis.millis)(materializer)
        .toJava

      def asScala: sm.ws.BinaryMessage = sm.ws.BinaryMessage(dataStream.asScala)
    }

  def adapt(msg: sm.ws.BinaryMessage): BinaryMessage = msg match {
    case sm.ws.BinaryMessage.Strict(data) => create(data)
    case bm: sm.ws.BinaryMessage          => create(bm.dataStream.asJava)
  }
}
