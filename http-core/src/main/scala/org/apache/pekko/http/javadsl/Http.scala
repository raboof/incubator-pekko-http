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
import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.util.Try
import com.typesafe.sslconfig.pekko.PekkoSSLConfig
import org.apache.pekko
import pekko.{ stream, NotUsed }
import pekko.actor.{ ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, ExtensionId, ExtensionIdProvider }
import pekko.event.LoggingAdapter
import pekko.http._
import pekko.http.impl.util.JavaMapping
import pekko.http.impl.util.JavaMapping.HttpsConnectionContext
import pekko.http.impl.util.JavaMapping.Implicits._
import pekko.http.javadsl.model._
import pekko.http.scaladsl.{ model => sm }
import pekko.http.javadsl.model.ws._
import pekko.http.javadsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import pekko.japi.Pair
import pekko.stream.TLSProtocol._
import pekko.stream.Materializer
import pekko.stream.javadsl.{ BidiFlow, Flow, Source }
import pekko.stream.scaladsl.Keep

object Http extends ExtensionId[Http] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http = super.get(system)
  override def get(system: ClassicActorSystemProvider): Http = super.get(system)
  def lookup() = Http
  def createExtension(system: ExtendedActorSystem): Http = new Http(system)
}

class Http(system: ExtendedActorSystem) extends pekko.actor.Extension {
  import pekko.dispatch.ExecutionContexts.{ sameThreadExecutionContext => ec }

  import language.implicitConversions
  private implicit def completionStageCovariant[T, U >: T](in: CompletionStage[T]): CompletionStage[U] =
    in.asInstanceOf[CompletionStage[U]]
  private implicit def javaModelIsScalaModel[J <: AnyRef, S <: J](in: Future[J])(
      implicit ev: JavaMapping.Inherited[J, S]): Future[S] = ev.downcast(in)

  private lazy val delegate = pekko.http.scaladsl.Http(system)

  /**
   * Constructs a server layer stage using the configured default [[pekko.http.javadsl.settings.ServerSettings]]. The returned [[pekko.stream.javadsl.BidiFlow]] isn't
   * reusable and can only be materialized once.
   */
  def serverLayer(): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer())

  /**
   * Constructs a server layer stage using the given [[pekko.http.javadsl.settings.ServerSettings]]. The returned [[pekko.stream.javadsl.BidiFlow]] isn't reusable and
   * can only be materialized once.
   */
  def serverLayer(
      settings: ServerSettings): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings.asScala))

  /**
   * Constructs a server layer stage using the given [[pekko.http.javadsl.settings.ServerSettings]]. The returned [[pekko.stream.javadsl.BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `pekko.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(
      settings: ServerSettings,
      remoteAddress: Optional[InetSocketAddress])
      : BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings.asScala, remoteAddress.asScala))

  /**
   * Constructs a server layer stage using the given [[ServerSettings]]. The returned [[pekko.stream.javadsl.BidiFlow]] isn't reusable and
   * can only be materialized once. The remoteAddress, if provided, will be added as a header to each [[HttpRequest]]
   * this layer produces if the `pekko.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(
      settings: ServerSettings,
      remoteAddress: Optional[InetSocketAddress],
      log: LoggingAdapter): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    adaptServerLayer(delegate.serverLayer(settings.asScala, remoteAddress.asScala, log))

  /**
   * Main entry point to create a server binding.
   *
   * @param interface The interface to bind to.
   * @param port The port to bind to or `0` if the port should be automatically assigned.
   */
  def newServerAt(interface: String, port: Int): ServerBuilder = ServerBuilder(interface, port, system)

  /**
   * Creates a [[pekko.stream.javadsl.Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).connectionSource() instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bind(connect: ConnectHttp): Source[IncomingConnection, CompletionStage[ServerBinding]] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    new Source(delegate.bind(connect.host, connect.port, connectionContext)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec).toJava))
  }

  /**
   * Creates a [[pekko.stream.javadsl.Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).withSettings(settings).connectionSource() instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bind(
      connect: ConnectHttp,
      settings: ServerSettings): Source[IncomingConnection, CompletionStage[ServerBinding]] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    new Source(delegate.bind(connect.host, connect.port, settings = settings.asScala,
      connectionContext = connectionContext)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec).toJava))
  }

  /**
   * Creates a [[pekko.stream.javadsl.Source]] of [[IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[ServerBinding]].
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).withSettings(settings).logTo(log).connectionSource() instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bind(
      connect: ConnectHttp,
      settings: ServerSettings,
      log: LoggingAdapter): Source[IncomingConnection, CompletionStage[ServerBinding]] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    new Source(delegate.bind(connect.host, connect.port, connectionContext, settings.asScala, log)
      .map(new IncomingConnection(_))
      .mapMaterializedValue(_.map(new ServerBinding(_))(ec).toJava))
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[pekko.stream.javadsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).bindFlow(handler) instead.
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandle(
      handler: Flow[HttpRequest, HttpResponse, _],
      connect: ConnectHttp,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandle(
      handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      connect.host, connect.port, connectionContext)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[pekko.stream.javadsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).withSettings(settings).logTo(log).bindFlow(handler) instead.
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandle(
      handler: Flow[HttpRequest, HttpResponse, _],
      connect: ConnectHttp,
      settings: ServerSettings,
      log: LoggingAdapter,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandle(
      handler.asInstanceOf[Flow[sm.HttpRequest, sm.HttpResponse, _]].asScala,
      connect.host, connect.port, connectionContext, settings.asScala, log)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * function for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).bindSync(handler) instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandleSync(
      handler: pekko.japi.Function[HttpRequest, HttpResponse],
      connect: ConnectHttp,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandleSync(handler.apply(_).asScala, connect.host, connect.port, connectionContext)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * function for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).withSettings(settings).logTo(log).bindSync(handler) instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandleSync(
      handler: pekko.japi.Function[HttpRequest, HttpResponse],
      connect: ConnectHttp,
      settings: ServerSettings,
      log: LoggingAdapter,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandleSync(
      handler.apply(_).asScala,
      connect.host, connect.port, connectionContext, settings.asScala, log)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * function for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).bind(handler) instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandleAsync(
      handler: pekko.japi.Function[HttpRequest, CompletionStage[HttpResponse]],
      connect: ConnectHttp,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandleAsync(handler.apply(_).toScala, connect.host, connect.port, connectionContext)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * function for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * The server will be bound using HTTPS if the [[ConnectHttp]] object is configured with an [[HttpsConnectionContext]],
   * or the [[defaultServerHttpContext]] has been configured to be an [[HttpsConnectionContext]].
   *
   * @deprecated since Akka HTTP 10.2.0: Use Http.get(system).newServerAt(interface, port).withSettings(settings).logTo(log).bind(handler) instead
   */
  @Deprecated
  @deprecated("Use newServerAt instead", since = "Akka HTTP 10.2.0")
  def bindAndHandleAsync(
      handler: pekko.japi.Function[HttpRequest, CompletionStage[HttpResponse]],
      connect: ConnectHttp,
      settings: ServerSettings,
      parallelism: Int, log: LoggingAdapter,
      materializer: Materializer): CompletionStage[ServerBinding] = {
    val connectionContext = connect.effectiveConnectionContext(defaultServerHttpContext).asScala
    delegate.bindAndHandleAsync(
      handler.apply(_).toScala,
      connect.host, connect.port, connectionContext, settings.asScala, parallelism, log)(materializer)
      .map(new ServerBinding(_))(ec).toJava
  }

  /**
   * Constructs a client layer stage using the configured default [[pekko.http.javadsl.settings.ClientConnectionSettings]].
   */
  def clientLayer(
      hostHeader: headers.Host): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader)))

  /**
   * Constructs a client layer stage using the given [[pekko.http.javadsl.settings.ClientConnectionSettings]].
   */
  def clientLayer(
      hostHeader: headers.Host,
      settings: ClientConnectionSettings): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings.asScala))

  /**
   * Constructs a client layer stage using the given [[ClientConnectionSettings]].
   */
  def clientLayer(
      hostHeader: headers.Host,
      settings: ClientConnectionSettings,
      log: LoggingAdapter): BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    adaptClientLayer(delegate.clientLayer(JavaMapping.toScala(hostHeader), settings.asScala, log))

  /**
   * Creates a [[pekko.stream.javadsl.Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * If the hostname is given with an `https://` prefix, the default [[HttpsConnectionContext]] will be used.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnection(host: String): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]] =
    outgoingConnection(ConnectHttp.toHost(host))

  /**
   * Creates a [[pekko.stream.javadsl.Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * Use the [[ConnectHttp]] DSL to configure target host and whether HTTPS should be used.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnection(to: ConnectHttp): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]] =
    adaptOutgoingFlow {
      if (to.isHttps) delegate.outgoingConnectionHttps(to.host, to.port,
        to.effectiveHttpsConnectionContext(defaultClientHttpsContext).asScala)
      else delegate.outgoingConnection(to.host, to.port)
    }

  /**
   * Creates a [[pekko.stream.javadsl.Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnection(
      to: ConnectHttp,
      localAddress: Optional[InetSocketAddress],
      settings: ClientConnectionSettings,
      log: LoggingAdapter): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]] =
    adaptOutgoingFlow {
      if (to.isHttps)
        delegate.outgoingConnectionHttps(to.host, to.port,
          to.effectiveConnectionContext(defaultClientHttpsContext).asInstanceOf[HttpsConnectionContext].asScala,
          localAddress.asScala, settings.asScala, log)
      else
        delegate.outgoingConnection(to.host, to.port, localAddress.asScala, settings.asScala, log)
    }

  /**
   * Creates a builder which will create a single connection to a host every time the built flow is materialized. There
   * is no pooling and you are yourself responsible for lifecycle management of the connection. For a more convenient
   * Request level API see [[singleRequest()]]
   *
   * @return A builder to configure more specific setup for the connection and then build a `Flow&gt;Request, Response, CompletionStage&gt;OutgoingConnection>>`.
   */
  def connectionTo(host: String): OutgoingConnectionBuilder =
    delegate.connectionTo(host).toJava

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[pekko.stream.javadsl.Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](host: String, materializer: Materializer)
      : Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    newHostConnectionPool[T](ConnectHttp.toHost(host), materializer)

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[pekko.stream.javadsl.Flow]] which dispatches
   * the requests from all its materializations across this pool.
   * While the started host connection pool internally shuts itself down automatically after the configured idle
   * timeout it will spin itself up again if more requests arrive from an existing or a new client flow
   * materialization. The returned flow therefore remains usable for the full lifetime of the application.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def newHostConnectionPool[T](to: ConnectHttp, materializer: Materializer)
      : Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.newHostConnectionPool[T](to.host, to.port)(materializer).mapMaterializedValue(_.toJava))

  /**
   * Same as [[newHostConnectionPool]] but with HTTPS encryption.
   *
   * The given [[ConnectionContext]] will be used for encryption on the connection.
   */
  def newHostConnectionPool[T](
      to: ConnectHttp,
      settings: ConnectionPoolSettings,
      log: LoggingAdapter, materializer: Materializer)
      : Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow {
      to.effectiveHttpsConnectionContext(defaultClientHttpsContext) match {
        case https: HttpsConnectionContext =>
          delegate.newHostConnectionPoolHttps[T](to.host, to.port, https.asScala, settings.asScala, log)(materializer)
            .mapMaterializedValue(_.toJava)
        case _ =>
          delegate.newHostConnectionPool[T](to.host, to.port, settings.asScala, log)(materializer)
            .mapMaterializedValue(_.toJava)
      }
    }

  /**
   * Returns a [[pekko.stream.javadsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[pekko.stream.javadsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](
      host: String): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    cachedHostConnectionPool(ConnectHttp.toHost(host))

  /**
   * Returns a [[pekko.stream.javadsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[pekko.stream.javadsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def cachedHostConnectionPool[T](
      to: ConnectHttp): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](to.host, to.port).mapMaterializedValue(_.toJava))

  /**
   * Returns a [[pekko.stream.javadsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[pekko.stream.javadsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
   *
   * The internal caching logic guarantees that there will never be more than a single pool running for the
   * given target host endpoint and configuration (in this ActorSystem).
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests A and B enter the flow in that order the response for B might be produced before the
   * response for A.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `pekko.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPool[T](
      to: ConnectHttp,
      settings: ConnectionPoolSettings,
      log: LoggingAdapter): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPool[T](to.host, to.port, settings.asScala, log).mapMaterializedValue(
      _.toJava))

  /**
   * Same as [[cachedHostConnectionPool]] but with HTTPS encryption.
   *
   * When an [[HttpConnectionContext]] is defined in the given [[ConnectHttp]] it will be used, otherwise the default client-side context will be used.
   */
  def cachedHostConnectionPoolHttps[T](
      to: ConnectHttp,
      settings: ConnectionPoolSettings,
      log: LoggingAdapter): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPoolHttps[T](to.host, to.port,
      to.effectiveHttpsConnectionContext(defaultClientHttpsContext).asScala, settings.asScala, log)
      .mapMaterializedValue(_.toJava))

  /**
   * Same as [[cachedHostConnectionPool]] but with HTTPS encryption.
   *
   * When an [[HttpConnectionContext]] is defined in the given [[ConnectHttp]] it will be used, otherwise the default client-side context will be used.
   */
  def cachedHostConnectionPoolHttps[T](
      to: ConnectHttp): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], HostConnectionPool] =
    adaptTupleFlow(delegate.cachedHostConnectionPoolHttps[T](to.host, to.port,
      to.effectiveHttpsConnectionContext(defaultClientHttpsContext).asScala)
      .mapMaterializedValue(_.toJava))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the flow in that order the response for `B` might be produced before the
   * response for `A`.
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T]())

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * The given [[HttpsConnectionContext]] is used to configure TLS for the connection.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the `flow` in that order the response for `B` might be produced before the
   * response for `A`.
   *
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](
      settings: ConnectionPoolSettings,
      connectionContext: HttpsConnectionContext,
      log: LoggingAdapter): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T](connectionContext.asScala, settings.asScala, log))

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have either an absolute URI or
   * a valid `Host` header.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Since the underlying transport usually comprises more than a single connection the produced flow might generate
   * responses in an order that doesn't directly match the consumed requests.
   * For example, if two requests `A` and `B` enter the `flow` in that order the response for `B` might be produced before the
   * response for `A`.
   *
   * In order to allow for easy response-to-request association the flow takes in a custom, opaque context
   * object of type `T` from the application which is emitted together with the corresponding response.
   */
  def superPool[T](
      settings: ConnectionPoolSettings,
      log: LoggingAdapter): Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], NotUsed] =
    adaptTupleFlow(delegate.superPool[T](defaultClientHttpsContext.asScala, settings.asScala, log))

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest): CompletionStage[HttpResponse] =
    delegate.singleRequest(request.asScala).toJava

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(request: HttpRequest, connectionContext: HttpsConnectionContext): CompletionStage[HttpResponse] =
    delegate.singleRequest(request.asScala, connectionContext.asScala).toJava

  /**
   * Fires a single [[HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * The given [[HttpsConnectionContext]] will be used for encryption if the request is sent to an https endpoint.
   *
   * Note that the request must have either an absolute URI or a valid `Host` header, otherwise
   * the future will be completed with an error.
   */
  def singleRequest(
      request: HttpRequest,
      connectionContext: HttpsConnectionContext,
      settings: ConnectionPoolSettings,
      log: LoggingAdapter): CompletionStage[HttpResponse] =
    delegate.singleRequest(request.asScala, connectionContext.asScala, settings.asScala, log).toJava

  /**
   * Constructs a WebSocket [[pekko.stream.javadsl.BidiFlow]].
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(request: WebSocketRequest)
      : BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala))

  /**
   * Constructs a WebSocket [[pekko.stream.javadsl.BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `pekko.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(
      request: WebSocketRequest,
      settings: ClientConnectionSettings)
      : BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala, settings.asScala))

  /**
   * Constructs a WebSocket [[pekko.stream.javadsl.BidiFlow]] using the configured default [[ClientConnectionSettings]],
   * configured using the `pekko.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(
      request: WebSocketRequest,
      settings: ClientConnectionSettings,
      log: LoggingAdapter)
      : BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage[WebSocketUpgradeResponse]] =
    adaptWsBidiFlow(delegate.webSocketClientLayer(request.asScala, settings.asScala, log))

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(
      request: WebSocketRequest): Flow[Message, Message, CompletionStage[WebSocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.webSocketClientFlow(request.asScala)
    }

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(
      request: WebSocketRequest,
      connectionContext: ConnectionContext,
      localAddress: Optional[InetSocketAddress],
      settings: ClientConnectionSettings,
      log: LoggingAdapter): Flow[Message, Message, CompletionStage[WebSocketUpgradeResponse]] =
    adaptWsFlow {
      delegate.webSocketClientFlow(request.asScala, connectionContext.asScala, localAddress.asScala, settings.asScala,
        log)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   */
  def singleWebSocketRequest[T](
      request: WebSocketRequest,
      clientFlow: Flow[Message, Message, T],
      materializer: Materializer): Pair[CompletionStage[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow))(materializer)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   *
   * The [[defaultClientHttpsContext]] is used to configure TLS for the connection.
   */
  def singleWebSocketRequest[T](
      request: WebSocketRequest,
      clientFlow: Flow[Message, Message, T],
      connectionContext: ConnectionContext,
      materializer: Materializer): Pair[CompletionStage[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow),
        connectionContext.asScala)(materializer)
    }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   */
  def singleWebSocketRequest[T](
      request: WebSocketRequest,
      clientFlow: Flow[Message, Message, T],
      connectionContext: ConnectionContext,
      localAddress: Optional[InetSocketAddress],
      settings: ClientConnectionSettings,
      log: LoggingAdapter,
      materializer: Materializer): Pair[CompletionStage[WebSocketUpgradeResponse], T] =
    adaptWsResultTuple {
      delegate.singleWebSocketRequest(
        request.asScala,
        adaptWsFlow[T](clientFlow),
        connectionContext.asScala,
        localAddress.asScala,
        settings.asScala,
        log)(materializer)
    }

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[pekko.actor.ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): CompletionStage[Unit] = delegate.shutdownAllConnectionPools().toJava

  /**
   * Gets the current default server-side [[ConnectionContext]] – defaults to plain HTTP.
   * Can be modified using [[setDefaultServerHttpContext]], and will then apply for servers bound after that call has completed.
   *
   * @deprecated since Akka HTTP 10.2.0: set context explicitly when binding
   */
  @Deprecated @deprecated("Set context explicitly when binding", since = "Akka HTTP 10.2.0")
  def defaultServerHttpContext: ConnectionContext =
    delegate.defaultServerHttpContext

  /**
   * Sets the default server-side [[ConnectionContext]].
   * If it is an instance of [[HttpsConnectionContext]] then the server will be bound using HTTPS.
   *
   * @deprecated since Akka HTTP 10.2.0: set context explicitly when binding
   */
  @Deprecated @deprecated("Set context explicitly when binding", since = "Akka HTTP 10.2.0")
  def setDefaultServerHttpContext(context: ConnectionContext): Unit =
    delegate.setDefaultServerHttpContext(context.asScala)

  /**
   * Gets the current default client-side [[ConnectionContext]].
   */
  def defaultClientHttpsContext: pekko.http.javadsl.HttpsConnectionContext = delegate.defaultClientHttpsContext

  /**
   * Sets the default client-side [[ConnectionContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsConnectionContext): Unit =
    delegate.setDefaultClientHttpsContext(context.asScala)

  @deprecated("use ConnectionContext.httpsServer", since = "10.2.0")
  def createServerHttpsContext(sslConfig: PekkoSSLConfig): HttpsConnectionContext =
    delegate.createServerHttpsContext(sslConfig)

  @deprecated("use ConnectionContext.httpsClient", since = "10.2.0")
  def createClientHttpsContext(sslConfig: PekkoSSLConfig): HttpsConnectionContext =
    delegate.createClientHttpsContext(sslConfig)

  @deprecated("use ConnectionContext.httpsClient", since = "10.2.0")
  def createDefaultClientHttpsContext(): HttpsConnectionContext =
    delegate.createDefaultClientHttpsContext()

  private def adaptTupleFlow[T, Mat](
      scalaFlow: stream.scaladsl.Flow[(scaladsl.model.HttpRequest, T), (Try[scaladsl.model.HttpResponse], T), Mat])
      : Flow[Pair[HttpRequest, T], Pair[Try[HttpResponse], T], Mat] = {
    implicit def id[X] = JavaMapping.identity[X]
    JavaMapping.toJava(scalaFlow)(JavaMapping.flowMapping[Pair[HttpRequest, T], (scaladsl.model.HttpRequest, T), Pair[
        Try[HttpResponse], T], (Try[scaladsl.model.HttpResponse], T), Mat, Mat])
  }

  private def adaptOutgoingFlow[T, Mat](
      scalaFlow: stream.scaladsl.Flow[scaladsl.model.HttpRequest, scaladsl.model.HttpResponse, Future[
          scaladsl.Http.OutgoingConnection]]): Flow[HttpRequest, HttpResponse, CompletionStage[OutgoingConnection]] =
    Flow.fromGraph {
      pekko.stream.scaladsl.Flow[HttpRequest].map(_.asScala)
        .viaMat(scalaFlow)(Keep.right)
        .mapMaterializedValue(_.map(new OutgoingConnection(_))(ec).toJava)
    }

  private def adaptServerLayer(serverLayer: scaladsl.Http.ServerLayer)
      : BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpResponse, sm.HttpResponse, sm.HttpRequest, HttpRequest]
        .atop(serverLayer))

  private def adaptClientLayer(clientLayer: scaladsl.Http.ClientLayer)
      : BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[HttpRequest, sm.HttpRequest, sm.HttpResponse, HttpResponse]
        .atop(clientLayer))

  private def adaptWsBidiFlow(wsLayer: scaladsl.Http.WebSocketClientLayer)
      : BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, CompletionStage[WebSocketUpgradeResponse]] =
    new BidiFlow(
      JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message]
        .atopMat(wsLayer)((_, s) => adaptWsUpgradeResponse(s)))

  private def adaptWsFlow(
      wsLayer: stream.scaladsl.Flow[sm.ws.Message, sm.ws.Message, Future[scaladsl.model.ws.WebSocketUpgradeResponse]])
      : Flow[Message, Message, CompletionStage[WebSocketUpgradeResponse]] =
    Flow.fromGraph(JavaMapping.adapterBidiFlow[Message, sm.ws.Message, sm.ws.Message, Message].joinMat(wsLayer)(
      Keep.right).mapMaterializedValue(adaptWsUpgradeResponse _))

  private def adaptWsFlow[Mat](javaFlow: Flow[Message, Message, Mat])
      : stream.scaladsl.Flow[scaladsl.model.ws.Message, scaladsl.model.ws.Message, Mat] =
    stream.scaladsl.Flow[scaladsl.model.ws.Message]
      .map(Message.adapt)
      .viaMat(javaFlow.asScala)(Keep.right)
      .map(_.asScala)

  private def adaptWsResultTuple[T](
      result: (Future[scaladsl.model.ws.WebSocketUpgradeResponse],
          T)): Pair[CompletionStage[WebSocketUpgradeResponse], T] =
    result match {
      case (fut, tMat) => Pair(adaptWsUpgradeResponse(fut), tMat)
    }
  private def adaptWsUpgradeResponse(
      responseFuture: Future[scaladsl.model.ws.WebSocketUpgradeResponse]): CompletionStage[WebSocketUpgradeResponse] =
    responseFuture.map(WebSocketUpgradeResponse.adapt)(system.dispatcher).toJava
}
