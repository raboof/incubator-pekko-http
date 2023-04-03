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

package org.apache.pekko.http.javadsl

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.ApiMayChange
import pekko.http.impl.util.JavaMapping
import pekko.http.impl.util.JavaMapping.Implicits._
import pekko.http.impl.util.JavaMapping._
import pekko.http.javadsl.model.headers.HttpCredentials
import pekko.http.javadsl.settings.ClientConnectionSettings
import pekko.http.{ javadsl, scaladsl }
import pekko.stream.javadsl.Flow
import pekko.util.ByteString
import scala.concurrent.Future

/**
 * (Still unstable) SPI for implementors of custom client transports.
 */
// #client-transport-definition
@ApiMayChange
abstract class ClientTransport {
  def connectTo(host: String, port: Int, settings: ClientConnectionSettings, system: ActorSystem)
      : Flow[ByteString, ByteString, CompletionStage[OutgoingConnection]]
}
// #client-transport-definition

/**
 * (Still unstable) entry point to create or access predefined client transports.
 */
@ApiMayChange
object ClientTransport {
  def TCP: ClientTransport = scaladsl.ClientTransport.TCP.asJava

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method.
   *
   * An HTTP(S) proxy is a proxy that will create one TCP connection to the HTTP(S) proxy for each target connection. The
   * proxy transparently forwards the TCP connection to the target host.
   *
   * For more information about HTTP CONNECT tunnelling see https://tools.ietf.org/html/rfc7231#section-4.3.6.
   */
  def httpsProxy(proxyAddress: InetSocketAddress): ClientTransport =
    scaladsl.ClientTransport.httpsProxy(proxyAddress).asJava

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method.
   *
   * Pulls the host/port pair from the application.conf: pekko.client.proxy.https.{host, port}
   */
  def httpsProxy(implicit system: ActorSystem): ClientTransport =
    scaladsl.ClientTransport.httpsProxy().asJava

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method. This call also takes [[HttpCredentials]] to base proxy credentials along with
   * the request.
   *
   * An HTTP(S) proxy is a proxy that will create one TCP connection to the HTTP(S) proxy for each target connection. The
   * proxy transparently forwards the TCP connection to the target host.
   *
   * For more information about HTTP CONNECT tunnelling see https://tools.ietf.org/html/rfc7231#section-4.3.6.
   */
  def httpsProxy(proxyAddress: InetSocketAddress, proxyCredentials: HttpCredentials): ClientTransport =
    scaladsl.ClientTransport.httpsProxy(proxyAddress, proxyCredentials.asScala).asJava

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method. This method also takes [[HttpCredentials]] in order to pass along to the proxy.
   *
   * Pulls the host/port pair from the application.conf: pekko.client.proxy.https.{host, port}
   */
  def httpsProxy(proxyCredentials: HttpCredentials, system: ActorSystem): ClientTransport =
    scaladsl.ClientTransport.httpsProxy(proxyCredentials.asScala)(system).asJava

  /**
   * Returns a [[ClientTransport]] that allows to customize host name resolution.
   * @param lookup A function that will be called with hostname and port and that should (potentially asynchronously resolve the given host/port
   *               to an [[InetSocketAddress]]
   */
  def withCustomResolver(lookup: BiFunction[String, Int, CompletionStage[InetSocketAddress]]): ClientTransport = {
    import scala.compat.java8.FutureConverters._
    scaladsl.ClientTransport.withCustomResolver((host, port) => lookup.apply(host, port).toScala).asJava
  }

  def fromScala(scalaTransport: scaladsl.ClientTransport): ClientTransport =
    scalaTransport match {
      case j: JavaWrapper => j.delegate
      case x              => new ScalaWrapper(x)
    }
  def toScala(javaTransport: ClientTransport): scaladsl.ClientTransport =
    javaTransport match {
      case s: ScalaWrapper => s.delegate
      case x               => new JavaWrapper(x)
    }

  private class ScalaWrapper(val delegate: scaladsl.ClientTransport) extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings, system: ActorSystem)
        : pekko.stream.javadsl.Flow[ByteString, ByteString, CompletionStage[javadsl.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toJava(delegate.connectTo(host, port, settings.asScala)(system))
    }
  }
  private class JavaWrapper(val delegate: ClientTransport) extends scaladsl.ClientTransport {
    def connectTo(host: String, port: Int, settings: scaladsl.settings.ClientConnectionSettings)(
        implicit system: ActorSystem)
        : pekko.stream.scaladsl.Flow[ByteString, ByteString, Future[scaladsl.Http.OutgoingConnection]] = {
      import system.dispatcher
      JavaMapping.toScala(delegate.connectTo(host, port, settings.asJava, system))
    }
  }
}
