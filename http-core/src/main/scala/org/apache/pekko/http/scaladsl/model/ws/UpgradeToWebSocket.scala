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

package org.apache.pekko.http.scaladsl.model.ws

import java.lang.Iterable
import scala.collection.immutable
import org.apache.pekko
import pekko.NotUsed
import pekko.stream._
import pekko.http.impl.util.JavaMapping
import pekko.http.javadsl.{ model => jm }
import pekko.http.scaladsl.model.HttpResponse

/**
 * A custom header that will be added to an WebSocket upgrade HttpRequest that
 * enables a request handler to upgrade this connection to a WebSocket connection and
 * registers a WebSocket handler.
 */
@deprecated("This low-level API has been replaced by an attribute.", since = "Akka HTTP 10.2.0")
trait UpgradeToWebSocket extends jm.ws.UpgradeToWebSocket with WebSocketUpgrade {

  /**
   * A sequence of protocols the client accepts.
   *
   * See http://tools.ietf.org/html/rfc6455#section-1.9
   */
  override def requestedProtocols: immutable.Seq[String]

  /**
   * The high-level interface to create a WebSocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to WebSocket and
   * use the supplied handler to handle incoming WebSocket messages.
   *
   * Optionally, a subprotocol out of the ones requested by the client can be chosen.
   */
  override def handleMessages(
      handlerFlow: Graph[FlowShape[Message, Message], Any],
      subprotocol: Option[String] = None): HttpResponse

  /**
   * The high-level interface to create a WebSocket server based on "messages".
   *
   * Returns a response to return in a request handler that will signal the
   * low-level HTTP implementation to upgrade the connection to WebSocket and
   * use the supplied inSink to consume messages received from the client and
   * the supplied outSource to produce message to sent to the client.
   *
   * Optionally, a subprotocol out of the ones requested by the client can be chosen.
   */
  override def handleMessagesWithSinkSource(
      inSink: Graph[SinkShape[Message], Any],
      outSource: Graph[SourceShape[Message], Any],
      subprotocol: Option[String] = None): HttpResponse =
    handleMessages(scaladsl.Flow.fromSinkAndSource(inSink, outSource), subprotocol)

  import scala.collection.JavaConverters._

  /**
   * Java API
   */
  override def getRequestedProtocols(): Iterable[String] = requestedProtocols.asJava

  /**
   * Java API
   */
  override def handleMessagesWith(handlerFlow: Graph[FlowShape[jm.ws.Message, jm.ws.Message], _ <: Any]): HttpResponse =
    handleMessages(JavaMapping.toScala(handlerFlow))

  /**
   * Java API
   */
  override def handleMessagesWith(handlerFlow: Graph[FlowShape[jm.ws.Message, jm.ws.Message], _ <: Any],
      subprotocol: String): HttpResponse =
    handleMessages(JavaMapping.toScala(handlerFlow), subprotocol = Some(subprotocol))

  /**
   * Java API
   */
  override def handleMessagesWith(inSink: Graph[SinkShape[jm.ws.Message], _ <: Any],
      outSource: Graph[SourceShape[jm.ws.Message], _ <: Any]): HttpResponse =
    handleMessages(createScalaFlow(inSink, outSource))

  /**
   * Java API
   */
  override def handleMessagesWith(
      inSink: Graph[SinkShape[jm.ws.Message], _ <: Any],
      outSource: Graph[SourceShape[jm.ws.Message], _ <: Any],
      subprotocol: String): HttpResponse =
    handleMessages(createScalaFlow(inSink, outSource), subprotocol = Some(subprotocol))

  private[this] def createScalaFlow(inSink: Graph[SinkShape[jm.ws.Message], _ <: Any],
      outSource: Graph[SourceShape[jm.ws.Message], _ <: Any]): Graph[FlowShape[Message, Message], NotUsed] =
    JavaMapping.toScala(scaladsl.Flow.fromSinkAndSourceMat(inSink, outSource)(scaladsl.Keep.none): Graph[FlowShape[
        jm.ws.Message, jm.ws.Message], NotUsed])
}
