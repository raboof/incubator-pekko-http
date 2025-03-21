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

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage
import javax.net.ssl._
import org.apache.pekko
import pekko.actor._
import pekko.annotation.{ DoNotInherit, InternalApi, InternalStableApi }
import pekko.dispatch.ExecutionContexts
import pekko.event.{ Logging, LoggingAdapter }
import pekko.http.impl.engine.HttpConnectionIdleTimeoutBidi
import pekko.http.impl.engine.client._
import pekko.http.impl.engine.http2.Http2
import pekko.http.impl.engine.http2.OutgoingConnectionBuilderImpl
import pekko.http.impl.engine.rendering.DateHeaderRendering
import pekko.http.impl.engine.server._
import pekko.http.impl.engine.ws.WebSocketClientBlueprint
import pekko.http.impl.settings.{ ConnectionPoolSetup, HostConnectionPoolSetup }
import pekko.http.impl.util.StreamUtils
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.Host
import pekko.http.scaladsl.model.ws.{ Message, WebSocketRequest, WebSocketUpgradeResponse }
import pekko.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, ServerSettings }
import pekko.http.scaladsl.util.FastFuture
import pekko.stream.Attributes.CancellationStrategy
import pekko.stream.Attributes.CancellationStrategy.AfterDelay
import pekko.stream.Attributes.CancellationStrategy.FailStage
import pekko.{ Done, NotUsed }
import pekko.stream._
import pekko.stream.TLSProtocol._
import pekko.stream.scaladsl._
import pekko.util.ByteString
import pekko.util.ManifestInfo

import scala.annotation.nowarn
import com.typesafe.config.Config
import com.typesafe.sslconfig.pekko._
import com.typesafe.sslconfig.pekko.util.PekkoLoggerFactory
import com.typesafe.sslconfig.ssl.ConfigSSLContextBuilder

import scala.concurrent._
import scala.util.{ Success, Try }
import scala.util.control.NonFatal
import scala.compat.java8.FutureConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.duration._

/**
 * Akka extension for HTTP which serves as the main entry point into pekko-http.
 *
 * Use as `Http().bindAndHandle` etc. with an implicit [[ActorSystem]] in scope.
 */
@nowarn("msg=DefaultSSLContextCreation in package scaladsl is deprecated")
@DoNotInherit
class HttpExt @InternalStableApi /* constructor signature is hardcoded in Telemetry */ private[http] (
    private val config: Config)(implicit val system: ExtendedActorSystem) extends pekko.actor.Extension
    with DefaultSSLContextCreation {

  pekko.http.Version.check(system.settings.config)
  pekko.PekkoVersion.require("pekko-http", pekko.http.Version.supportedPekkoVersion)

  // Used for ManifestInfo.checkSameVersion
  private def allModules: List[String] = List(
    "pekko-parsing",
    "pekko-http-core",
    "pekko-http",
    "pekko-http-caching",
    "pekko-http-testkit",
    "pekko-http-marshallers-scala",
    "pekko-http-marshallers-java",
    "pekko-http-spray-json",
    "pekko-http-xml",
    "pekko-http-jackson")

  ManifestInfo(system).checkSameVersion("Akka HTTP", allModules, logWarning = true)

  import Http._

  private[this] val defaultConnectionPoolSettings = ConnectionPoolSettings(system)

  // configured default HttpsContext for the client-side
  // SYNCHRONIZED ACCESS ONLY!
  private[this] var _defaultClientHttpsConnectionContext: HttpsConnectionContext = _
  private[this] var _defaultServerConnectionContext: ConnectionContext = _

  // ** SERVER ** //

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  // Date header rendering is shared across the system, so that date is only rendered once a second
  private[http] val dateHeaderRendering = DateHeaderRendering()

  private type ServerLayerBidiFlow = BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator]
  private type ServerLayerFlow = Flow[ByteString, ByteString, (Future[Done], ServerTerminator)]

  private def fuseServerBidiFlow(
      settings: ServerSettings,
      connectionContext: ConnectionContext,
      log: LoggingAdapter): ServerLayerBidiFlow = {
    val httpLayer = serverLayer(settings, None, log, connectionContext.isSecure)
    val tlsStage = sslTlsServerStage(connectionContext)

    val serverBidiFlow =
      settings.idleTimeout match {
        case t: FiniteDuration => httpLayer.atop(tlsStage).atop(HttpConnectionIdleTimeoutBidi(t, None))
        case _                 => httpLayer.atop(tlsStage)
      }

    GracefulTerminatorStage(system, settings).atop(serverBidiFlow)
  }

  private def delayCancellationStage(
      settings: ServerSettings): BidiFlow[SslTlsOutbound, SslTlsOutbound, SslTlsInbound, SslTlsInbound, NotUsed] =
    BidiFlow.fromFlows(Flow[SslTlsOutbound], StreamUtils.delayCancellation(settings.lingerTimeout))

  private def fuseServerFlow(
      baseFlow: ServerLayerBidiFlow,
      handler: Flow[HttpRequest, HttpResponse, Any]): ServerLayerFlow =
    Flow.fromGraph(
      Flow[HttpRequest]
        .watchTermination()(Keep.right)
        .via(handler)
        .watchTermination() { (termWatchBefore, termWatchAfter) =>
          // flag termination when the user handler has gotten (or has emitted) termination
          // signals in both directions
          termWatchBefore.flatMap(_ => termWatchAfter)(ExecutionContexts.sameThreadExecutionContext)
        }
        .joinMat(baseFlow)(Keep.both))

  private def tcpBind(interface: String, port: Int, settings: ServerSettings)
      : Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] =
    Tcp()
      .bind(
        interface,
        port,
        settings.backlog,
        settings.socketOptions,
        halfClose = false,
        idleTimeout = Duration.Inf // we knowingly disable idle-timeout on TCP level, as we handle it explicitly in Akka HTTP itself
      )

  private def choosePort(port: Int, connectionContext: ConnectionContext, settings: ServerSettings) =
    if (port >= 0) port
    else if (connectionContext.isSecure) settings.defaultHttpsPort
    else settings.defaultHttpPort

  /**
   * Main entry point to create a server binding.
   *
   * @param interface The interface to bind to.
   * @param port The port to bind to or `0` if the port should be automatically assigned.
   */
  def newServerAt(interface: String, port: Int): ServerBuilder = ServerBuilder(interface, port, system)

  /**
   * Creates a [[pekko.stream.scaladsl.Source]] of [[pekko.http.scaladsl.Http.IncomingConnection]] instances which represents a prospective HTTP server binding
   * on the given `endpoint`.
   *
   * If the given port is 0 the resulting source can be materialized several times. Each materialization will
   * then be assigned a new local port by the operating system, which can then be retrieved by the materialized
   * [[pekko.http.scaladsl.Http.ServerBinding]].
   *
   * If the given port is non-zero subsequent materialization attempts of the produced source will immediately
   * fail, unless the first materialization has already been unbound. Unbinding can be triggered via the materialized
   * [[pekko.http.scaladsl.Http.ServerBinding]].
   *
   * If an [[ConnectionContext]] is given it will be used for setting up TLS encryption on the binding.
   * Otherwise the binding will be unencrypted.
   *
   * If no `port` is explicitly given (or the port value is negative) the protocol's default port will be used,
   * which is 80 for HTTP and 443 for HTTPS.
   *
   * To configure additional settings for a server started using this method,
   * use the `pekko.http.server` config section or pass in a [[pekko.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  @deprecated(
    "Use Http().newServerAt(...)...connectionSource() to create a source that can be materialized to a binding.",
    since = "10.2.0")
  @nowarn("msg=deprecated")
  def bind(interface: String, port: Int = DefaultPortForProtocol,
      connectionContext: ConnectionContext = defaultServerHttpContext,
      settings: ServerSettings = ServerSettings(system),
      log: LoggingAdapter = system.log): Source[Http.IncomingConnection, Future[ServerBinding]] = {
    if (settings.previewServerSettings.enableHttp2)
      log.warning(
        s"Binding with a connection source not supported with HTTP/2. Falling back to HTTP/1.1 for port [$port]")

    val fullLayer: ServerLayerBidiFlow = fuseServerBidiFlow(settings, connectionContext, log)

    val masterTerminator = new MasterServerTerminator(log)

    tcpBind(interface, choosePort(port, connectionContext, settings), settings)
      .map(incoming => {
        val preparedLayer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, ServerTerminator] =
          fullLayer.addAttributes(prepareAttributes(settings, incoming))
        val serverFlow: Flow[HttpResponse, HttpRequest, ServerTerminator] = preparedLayer.join(incoming.flow)
        IncomingConnection(incoming.localAddress, incoming.remoteAddress, serverFlow)
      })
      .mapMaterializedValue {
        _.map(tcpBinding =>
          ServerBinding(tcpBinding.localAddress)(
            () => tcpBinding.unbind(),
            timeout => masterTerminator.terminate(timeout)(systemMaterializer.executionContext)))(
          systemMaterializer.executionContext)
      }
  }

  // forwarder to allow internal code to call deprecated method without warning
  @nowarn("msg=deprecated")
  private[http] def bindImpl(interface: String, port: Int,
      connectionContext: ConnectionContext,
      settings: ServerSettings,
      log: LoggingAdapter): Source[Http.IncomingConnection, Future[ServerBinding]] =
    bind(interface, port, connectionContext, settings, log)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[pekko.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `pekko.http.server` config section or pass in a [[pekko.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  @deprecated("Use Http().newServerAt(...)...bindFlow() to create server bindings.", since = "10.2.0")
  @nowarn("msg=deprecated")
  def bindAndHandle(
      handler: Flow[HttpRequest, HttpResponse, Any],
      interface: String, port: Int = DefaultPortForProtocol,
      connectionContext: ConnectionContext = defaultServerHttpContext,
      settings: ServerSettings = ServerSettings(system),
      log: LoggingAdapter = system.log)(implicit fm: Materializer = systemMaterializer): Future[ServerBinding] = {
    if (settings.previewServerSettings.enableHttp2)
      log.warning(
        s"Binding with a connection source not supported with HTTP/2. Falling back to HTTP/1.1 for port [$port].")

    val fullLayer: Flow[ByteString, ByteString, (Future[Done], ServerTerminator)] =
      fuseServerFlow(fuseServerBidiFlow(settings, connectionContext, log), handler)

    val masterTerminator = new MasterServerTerminator(log)

    tcpBind(interface, choosePort(port, connectionContext, settings), settings)
      .mapAsyncUnordered(settings.maxConnections) { incoming =>
        try {
          fullLayer
            .watchTermination() {
              case ((done, connectionTerminator), whenTerminates) =>
                whenTerminates.onComplete { _ =>
                  masterTerminator.removeConnection(connectionTerminator)
                }(fm.executionContext)
                (done, connectionTerminator)
            }
            .addAttributes(prepareAttributes(settings, incoming))
            .join(incoming.flow)
            .mapMaterializedValue {
              case (future, connectionTerminator) =>
                masterTerminator.registerConnection(connectionTerminator)(fm.executionContext)
                future // drop the terminator matValue, we already registered is which is all we need to do here
            }
            .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))
            .run()
            .recover {
              // Ignore incoming errors from the connection as they will cancel the binding.
              // As far as it is known currently, these errors can only happen if a TCP error bubbles up
              // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
              // See https://github.com/akka/akka/issues/17992
              case NonFatal(ex) => Done
            }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) =>
            log.error(e, "Could not materialize handling flow for {}", incoming)
            throw e
        }
      }
      .mapMaterializedValue { m =>
        m.map(tcpBinding =>
          ServerBinding(
            tcpBinding.localAddress)(
            () => tcpBinding.unbind(),
            timeout => masterTerminator.terminate(timeout)(fm.executionContext)))(fm.executionContext)
      }
      .to(Sink.ignore)
      .run()
  }

  // forwarder to allow internal code to call deprecated method without warning
  @nowarn("msg=deprecated")
  private[http] def bindAndHandleImpl(
      handler: Flow[HttpRequest, HttpResponse, Any],
      interface: String, port: Int,
      connectionContext: ConnectionContext,
      settings: ServerSettings,
      log: LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandle(handler, interface, port, connectionContext, settings, log)(fm)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[pekko.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `pekko.http.server` config section or pass in a [[pekko.http.scaladsl.settings.ServerSettings]] explicitly.
   */
  @deprecated("Use Http().newServerAt(...)...bindSync() to create server bindings.", since = "10.2.0")
  @nowarn("msg=deprecated")
  def bindAndHandleSync(
      handler: HttpRequest => HttpResponse,
      interface: String, port: Int = DefaultPortForProtocol,
      connectionContext: ConnectionContext = defaultServerHttpContext,
      settings: ServerSettings = ServerSettings(system),
      log: LoggingAdapter = system.log)(implicit fm: Materializer = systemMaterializer): Future[ServerBinding] =
    bindAndHandleAsync(req => FastFuture.successful(handler(req)), interface, port, connectionContext, settings,
      parallelism = 0, log)(fm)

  /**
   * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
   * [[pekko.stream.scaladsl.Flow]] for processing all incoming connections.
   *
   * The number of concurrently accepted connections can be configured by overriding
   * the `pekko.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
   * information about what kind of guarantees to expect.
   *
   * To configure additional settings for a server started using this method,
   * use the `pekko.http.server` config section or pass in a [[pekko.http.scaladsl.settings.ServerSettings]] explicitly.
   *
   * Parameter `parallelism` specifies how many requests are attempted to be handled concurrently per connection. In HTTP/1
   * this makes only sense if HTTP pipelining is enabled (which is not recommended). The default value of `0` means that
   * the value is taken from the `pekko.http.server.pipelining-limit` setting from the configuration. In HTTP/2,
   * the default value is taken from `pekko.http.server.http2.max-concurrent-streams`.
   *
   * Any other value for `parallelism` overrides the setting.
   */
  @deprecated("Use Http().newServerAt(...)...bind() to create server bindings.", since = "10.2.0")
  @nowarn("msg=deprecated")
  def bindAndHandleAsync(
      handler: HttpRequest => Future[HttpResponse],
      interface: String, port: Int = DefaultPortForProtocol,
      connectionContext: ConnectionContext = defaultServerHttpContext,
      settings: ServerSettings = ServerSettings(system),
      parallelism: Int = 0,
      log: LoggingAdapter = system.log)(implicit fm: Materializer = systemMaterializer): Future[ServerBinding] = {
    if (settings.previewServerSettings.enableHttp2) {
      log.debug("Binding server using HTTP/2")

      val definitiveSettings =
        if (parallelism > 0) settings.mapHttp2Settings(_.withMaxConcurrentStreams(parallelism))
        else if (parallelism < 0) throw new IllegalArgumentException("Only positive values allowed for `parallelism`.")
        else settings
      Http2().bindAndHandleAsync(handler, interface, port, connectionContext, definitiveSettings, log)(fm)
    } else {
      val definitiveParallelism =
        if (parallelism > 0) parallelism
        else if (parallelism < 0) throw new IllegalArgumentException("Only positive values allowed for `parallelism`.")
        else settings.pipeliningLimit
      bindAndHandleImpl(Flow[HttpRequest].mapAsync(definitiveParallelism)(handler), interface, port, connectionContext,
        settings, log)
    }
  }

  // forwarder to allow internal code to call deprecated method without warning
  @nowarn("msg=deprecated")
  private[http] def bindAndHandleAsyncImpl(
      handler: HttpRequest => Future[HttpResponse],
      interface: String, port: Int,
      connectionContext: ConnectionContext,
      settings: ServerSettings,
      parallelism: Int,
      log: LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] =
    bindAndHandleAsync(handler, interface, port, connectionContext, settings, parallelism, log)(fm)

  type ServerLayer = Http.ServerLayer

  /**
   * Constructs a [[pekko.http.scaladsl.Http.ServerLayer]] stage using the given [[pekko.http.scaladsl.settings.ServerSettings]]. The returned [[pekko.stream.scaladsl.BidiFlow]] isn't reusable and
   * can only be materialized once. The `remoteAddress`, if provided, will be added as a header to each [[pekko.http.scaladsl.model.HttpRequest]]
   * this layer produces if the `pekko.http.server.remote-address-header` configuration option is enabled.
   */
  def serverLayer(
      settings: ServerSettings = ServerSettings(system),
      remoteAddress: Option[InetSocketAddress] = None,
      log: LoggingAdapter = system.log,
      isSecureConnection: Boolean = false): ServerLayer = {
    val server = HttpServerBluePrint(settings, log, isSecureConnection, dateHeaderRendering)
      .addAttributes(HttpAttributes.remoteAddress(remoteAddress))
      .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))

    server.atop(delayCancellationStage(settings))
  }

  // ** CLIENT ** //

  private[http] val poolMaster: PoolMaster = PoolMaster()
  private[this] val systemMaterializer = SystemMaterializer(system).materializer

  /**
   * Creates a builder which will create a single connection to a host every time the built flow is materialized. There
   * is no pooling and you are yourself responsible for lifecycle management of the connection. For a more convenient
   * Request level API see [[singleRequest()]]
   *
   * @return A builder to configure more specific setup for the connection and then build a `Flow[Request, Response, Future[OutgoingConnection]]`.
   */
  def connectionTo(host: String): OutgoingConnectionBuilder = OutgoingConnectionBuilderImpl(host, system)

  /**
   * Creates a [[pekko.stream.scaladsl.Flow]] representing a prospective HTTP client connection to the given endpoint.
   * Every materialization of the produced flow will attempt to establish a new outgoing connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `pekko.http.client` config section or pass in a [[pekko.http.scaladsl.settings.ClientConnectionSettings]] explicitly.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnection(host: String, port: Int = 80,
      localAddress: Option[InetSocketAddress] = None,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, settings.withLocalAddressOverride(localAddress), ConnectionContext.noEncryption(),
      log)

  /**
   * Same as [[#outgoingConnection]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[HttpsConnectionContext]] is given then it rather than the configured default [[HttpsConnectionContext]] will be used
   * for encryption on the connection.
   *
   * To configure additional settings for requests made using this method,
   * use the `pekko.http.client` config section or pass in a [[pekko.http.scaladsl.settings.ClientConnectionSettings]] explicitly.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnectionHttps(host: String, port: Int = 443,
      connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
      localAddress: Option[InetSocketAddress] = None,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, settings.withLocalAddressOverride(localAddress), connectionContext, log)

  /**
   * Similar to `outgoingConnection` but allows to specify a user-defined context to run the connection on.
   *
   * Depending on the kind of `ConnectionContext` the implementation will add TLS between the given transport and the HTTP
   * implementation
   *
   * To configure additional settings for requests made using this method,
   * use the `pekko.http.client` config section or pass in a [[pekko.http.scaladsl.settings.ClientConnectionSettings]] explicitly.
   *
   * Prefer [[connectionTo]] over this method.
   */
  def outgoingConnectionUsingContext(
      host: String,
      port: Int,
      connectionContext: ConnectionContext,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
    _outgoingConnection(host, port, settings, connectionContext, log)

  private def _outgoingConnection(
      host: String,
      port: Int,
      settings: ClientConnectionSettings,
      connectionContext: ConnectionContext,
      log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
    val hostHeader = port match {
      case 0                                 => Host(host)
      case 80 if !connectionContext.isSecure => Host(host)
      case 443 if connectionContext.isSecure => Host(host)
      case _                                 => Host(host, port)
    }
    val layer = clientLayer(hostHeader, settings, log)
    layer.joinMat(_outgoingTlsConnectionLayer(host, port, settings, connectionContext, log))(Keep.right)
      // already added in clientLayer but needed here again to also include transport layer
      .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))
  }

  private def _outgoingTlsConnectionLayer(host: String, port: Int,
      settings: ClientConnectionSettings, connectionContext: ConnectionContext,
      log: LoggingAdapter): Flow[SslTlsOutbound, SslTlsInbound, Future[OutgoingConnection]] = {
    val tlsStage = sslTlsClientStage(connectionContext, host, port)

    tlsStage.joinMat(settings.transport.connectTo(host, port, settings))(Keep.right)
  }

  type ClientLayer = Http.ClientLayer

  /**
   * Constructs a [[pekko.http.scaladsl.Http.ClientLayer]] stage using the configured default [[pekko.http.scaladsl.settings.ClientConnectionSettings]],
   * configured using the `pekko.http.client` config section.
   */
  def clientLayer(hostHeader: Host): ClientLayer =
    clientLayer(hostHeader, ClientConnectionSettings(system))

  /**
   * Constructs a [[pekko.http.scaladsl.Http.ClientLayer]] stage using the given [[pekko.http.scaladsl.settings.ClientConnectionSettings]].
   */
  def clientLayer(
      hostHeader: Host,
      settings: ClientConnectionSettings,
      log: LoggingAdapter = system.log): ClientLayer =
    OutgoingConnectionBlueprint(hostHeader, settings, log)
      .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))

  // ** CONNECTION POOL ** //

  /**
   * Starts a new connection pool to the given host and configuration and returns a [[pekko.stream.scaladsl.Flow]] which dispatches
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
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `pekko.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPool[T](host: String, port: Int = 80,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log)(
      implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings.forHost(host), ConnectionContext.noEncryption(), log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
  }

  /**
   * Same as [[#newHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `pekko.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def newHostConnectionPoolHttps[T](host: String, port: Int = 443,
      connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log)(
      implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings.forHost(host), connectionContext, log)
    newHostConnectionPool(HostConnectionPoolSetup(host, port, cps))
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[pekko] def newHostConnectionPool[T](setup: HostConnectionPoolSetup)(
      implicit
      fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val poolId = new PoolId(setup, PoolId.newUniquePool())
    poolMaster.startPool(poolId)
    poolClientFlow(poolId)
  }

  /**
   * Returns a [[pekko.stream.scaladsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[pekko.stream.scaladsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
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
  def cachedHostConnectionPool[T](host: String, port: Int = 80,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings.forHost(host), ConnectionContext.noEncryption(), log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Same as [[#cachedHostConnectionPool]] but for encrypted (HTTPS) connections.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for encryption on the connections.
   *
   * To configure additional settings for the pool (and requests made using it),
   * use the `pekko.http.host-connection-pool` config section or pass in a [[ConnectionPoolSettings]] explicitly.
   */
  def cachedHostConnectionPoolHttps[T](host: String, port: Int = 443,
      connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val cps = ConnectionPoolSetup(settings.forHost(host), connectionContext, log)
    val setup = HostConnectionPoolSetup(host, port, cps)
    cachedHostConnectionPool(setup)
  }

  /**
   * Returns a [[pekko.stream.scaladsl.Flow]] which dispatches incoming HTTP requests to the per-ActorSystem pool of outgoing
   * HTTP connections to the given target host endpoint. For every ActorSystem, target host and pool
   * configuration a separate connection pool is maintained.
   * The HTTP layer transparently manages idle shutdown and restarting of connections pools as configured.
   * The returned [[pekko.stream.scaladsl.Flow]] instances therefore remain valid throughout the lifetime of the application.
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
  private def cachedHostConnectionPool[T](
      setup: HostConnectionPoolSetup): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] = {
    val poolId = sharedPoolId(setup)
    poolMaster.startPool(poolId)
    poolClientFlow(poolId)
  }

  /**
   * Creates a new "super connection pool flow", which routes incoming requests to a (cached) host connection pool
   * depending on their respective effective URIs. Note that incoming requests must have an absolute URI.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for setting up HTTPS connection pools, if required.
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
  def superPool[T](
      connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] =
    clientFlow[T](settings)(request =>
      singleRequest(request, connectionContext, settings.forHost(request.uri.authority.host.toString), log))

  /**
   * Fires a single [[pekko.http.scaladsl.model.HttpRequest]] across the (cached) host connection pool for the request's
   * effective URI to produce a response future.
   *
   * If an explicit [[ConnectionContext]] is given then it rather than the configured default [[ConnectionContext]] will be used
   * for setting up the HTTPS connection pool, if the request is targeted towards an `https` endpoint.
   *
   * Note that the request must have an absolute URI, otherwise the future will be completed with an error.
   */
  def singleRequest(
      request: HttpRequest,
      connectionContext: HttpsConnectionContext = defaultClientHttpsContext,
      settings: ConnectionPoolSettings = defaultConnectionPoolSettings,
      log: LoggingAdapter = system.log): Future[HttpResponse] =
    try poolMaster.dispatchRequest(sharedPoolIdFor(request, settings.forHost(request.uri.authority.host.toString),
        connectionContext, log), request)
    catch {
      case e: IllegalUriException => FastFuture.failed(e)
    }

  /**
   * Constructs a [[pekko.http.scaladsl.Http.WebSocketClientLayer]] stage using the configured default [[pekko.http.scaladsl.settings.ClientConnectionSettings]],
   * configured using the `pekko.http.client` config section.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientLayer(
      request: WebSocketRequest,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log): Http.WebSocketClientLayer =
    WebSocketClientBlueprint(request, settings, log)
      .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))

  /**
   * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
   *
   * The layer is not reusable and must only be materialized once.
   */
  def webSocketClientFlow(
      request: WebSocketRequest,
      connectionContext: ConnectionContext = defaultClientHttpsContext,
      localAddress: Option[InetSocketAddress] = None,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    import request.uri
    require(uri.isAbsolute, s"WebSocket request URI must be absolute but was '$uri'")

    val ctx = uri.scheme match {
      case "ws"                                => ConnectionContext.noEncryption()
      case "wss" if connectionContext.isSecure => connectionContext
      case "wss" => throw new IllegalArgumentException(
          "Provided connectionContext is not secure, yet request to secure `wss` endpoint detected!")
      case scheme =>
        throw new IllegalArgumentException(s"Illegal URI scheme '$scheme' in '$uri' for WebSocket request. " +
          s"WebSocket requests must use either 'ws' or 'wss'")
    }
    val host = uri.authority.host.address
    val port = uri.effectivePort

    webSocketClientLayer(request, settings, log)
      .join(_outgoingTlsConnectionLayer(host, port, settings.withLocalAddressOverride(localAddress), ctx, log))
      // also added webSocketClientLayer but we want to make sure it covers the whole stack
      .addAttributes(cancellationStrategyAttributeForDelay(settings.streamCancellationDelay))
  }

  /**
   * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
   * WebSocket conversation.
   */
  def singleWebSocketRequest[T](
      request: WebSocketRequest,
      clientFlow: Flow[Message, Message, T],
      connectionContext: ConnectionContext = defaultClientHttpsContext,
      localAddress: Option[InetSocketAddress] = None,
      settings: ClientConnectionSettings = ClientConnectionSettings(system),
      log: LoggingAdapter = system.log)(implicit mat: Materializer): (Future[WebSocketUpgradeResponse], T) =
    webSocketClientFlow(request, connectionContext, localAddress, settings, log)
      .joinMat(clientFlow)(Keep.both).run()

  /**
   * Triggers an orderly shutdown of all host connections pools currently maintained by the [[pekko.actor.ActorSystem]].
   * The returned future is completed when all pools that were live at the time of this method call
   * have completed their shutdown process.
   *
   * If existing pool client flows are re-used or new ones materialized concurrently with or after this
   * method call the respective connection pools will be restarted and not contribute to the returned future.
   */
  def shutdownAllConnectionPools(): Future[Unit] = poolMaster.shutdownAll().map(_ => ())(system.dispatcher)

  /**
   * Gets the current default server-side [[ConnectionContext]] – defaults to plain HTTP.
   * Can be modified using [[setDefaultServerHttpContext]], and will then apply for servers bound after that call has completed.
   */
  @deprecated("Set context explicitly when binding", since = "10.2.0")
  def defaultServerHttpContext: ConnectionContext =
    synchronized {
      if (_defaultServerConnectionContext == null)
        _defaultServerConnectionContext = ConnectionContext.noEncryption()
      _defaultServerConnectionContext
    }

  /**
   * Sets the default server-side [[ConnectionContext]].
   * If it is an instance of [[HttpsConnectionContext]] then the server will be bound using HTTPS.
   */
  @deprecated("Set context explicitly when binding", since = "10.2.0")
  def setDefaultServerHttpContext(context: ConnectionContext): Unit =
    synchronized {
      _defaultServerConnectionContext = context
    }

  /**
   * Gets the current default client-side [[HttpsConnectionContext]].
   * Defaults used here can be configured using ssl-config or the context can be replaced using [[setDefaultClientHttpsContext]]
   */
  def defaultClientHttpsContext: HttpsConnectionContext =
    synchronized {
      _defaultClientHttpsConnectionContext match {
        case null =>
          val ctx = ConnectionContext.httpsClient(SSLContext.getDefault)
          _defaultClientHttpsConnectionContext = ctx
          ctx
        case ctx => ctx
      }
    }

  /**
   * Sets the default client-side [[HttpsConnectionContext]].
   */
  def setDefaultClientHttpsContext(context: HttpsConnectionContext): Unit =
    synchronized {
      _defaultClientHttpsConnectionContext = context
    }

  private def sharedPoolIdFor(request: HttpRequest, settings: ConnectionPoolSettings,
      connectionContext: ConnectionContext, log: LoggingAdapter): PoolId = {
    if (request.uri.scheme.nonEmpty && request.uri.authority.nonEmpty) {
      val httpsCtx =
        if (request.uri.scheme.equalsIgnoreCase("https")) connectionContext else ConnectionContext.noEncryption()
      val setup = ConnectionPoolSetup(settings, httpsCtx, log)
      val host = request.uri.authority.host.toString()
      val hcps = HostConnectionPoolSetup(host, request.uri.effectivePort, setup)
      sharedPoolId(hcps)
    } else {
      val msg =
        s"Cannot determine request scheme and target endpoint as ${request.method} request to ${request.uri} doesn't have an absolute URI"
      throw new IllegalUriException(ErrorInfo(msg))
    }
  }

  private def sharedPoolId(hcps: HostConnectionPoolSetup): PoolId =
    new PoolId(hcps, PoolId.SharedPool)

  private def poolClientFlow[T](poolId: PoolId): Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool] =
    clientFlow[T](poolId.hcps.setup.settings)(request => poolMaster.dispatchRequest(poolId, request))
      .mapMaterializedValue(_ => new HostConnectionPoolImpl(poolId, poolMaster))

  private def clientFlow[T](settings: ConnectionPoolSettings)(
      poolInterface: HttpRequest => Future[HttpResponse]): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] = {
    // a connection pool can never have more than pipeliningLimit * maxConnections requests in flight at any point
    // FIXME: that statement is wrong since this method is used for the superPool as well which can comprise any number of target host pools.
    // The user should keep control over how much parallelism is required.
    val parallelism = settings.pipeliningLimit * settings.maxConnections
    Flow[(HttpRequest, T)].mapAsyncUnordered(parallelism) {
      case (request, userContext) => poolInterface(request).transform(response => Success(response -> userContext))(
          ExecutionContexts.sameThreadExecutionContext)
    }
  }

  /** Creates real or placebo SslTls stage based on if ConnectionContext is HTTPS or not. */
  private[http] def sslTlsClientStage(connectionContext: ConnectionContext, host: String, port: Int) =
    sslTlsStage(connectionContext, Client, Some((host, port)))

  private[http] def sslTlsServerStage(connectionContext: ConnectionContext) =
    sslTlsStage(connectionContext, Server, None)

  private def sslTlsStage(connectionContext: ConnectionContext, role: TLSRole, hostInfo: Option[(String, Int)]) =
    connectionContext match {
      case hctx: HttpsConnectionContext =>
        hctx.sslContextData match {
          case Left(ssl) =>
            TLS(ssl.sslContext, ssl.sslConfig, ssl.firstSession, role, hostInfo = hostInfo,
              closing = TLSClosing.eagerClose)
          case Right(engineCreator) =>
            TLS(() => engineCreator(hostInfo), TLSClosing.eagerClose)
        }
      case other =>
        TLSPlacebo() // if it's not HTTPS, we don't enable SSL/TLS
    }

  /**
   * INTERNAL API
   *
   * For testing only
   */
  @InternalApi
  private[scaladsl] def poolSize: Future[Int] = poolMaster.poolSize()
}

object Http extends ExtensionId[HttpExt] with ExtensionIdProvider {

  // #server-layer
  /**
   * The type of the server-side HTTP layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP server.
   *
   * {{{
   *                +------+
   * HttpResponse ~>|      |~> SslTlsOutbound
   *                | bidi |
   * HttpRequest  <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type ServerLayer = BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed]
  // #server-layer

  // #client-layer
  /**
   * The type of the client-side HTTP layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP client.
   *
   * {{{
   *                +------+
   * HttpRequest  ~>|      |~> SslTlsOutbound
   *                | bidi |
   * HttpResponse <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type ClientLayer = BidiFlow[HttpRequest, SslTlsOutbound, SslTlsInbound, HttpResponse, NotUsed]
  // #client-layer

  /**
   * The type of the client-side WebSocket layer as a stand-alone BidiFlow
   * that can be put atop the TCP layer to form an HTTP client.
   *
   * {{{
   *                +------+
   * ws.Message   ~>|      |~> SslTlsOutbound
   *                | bidi |
   * ws.Message   <~|      |<~ SslTlsInbound
   *                +------+
   * }}}
   */
  type WebSocketClientLayer =
    BidiFlow[Message, SslTlsOutbound, SslTlsInbound, Message, Future[WebSocketUpgradeResponse]]

  /**
   * Represents a prospective HTTP server binding.
   *
   * @param localAddress  The local address of the endpoint bound by the materialization of the `connections` [[pekko.stream.scaladsl.Source]]
   */
  final case class ServerBinding(localAddress: InetSocketAddress)(
      private val unbindAction: () => Future[Unit],
      private val terminateAction: FiniteDuration => Future[HttpTerminated]) {

    private val _whenTerminationSignalIssued = Promise[Deadline]()
    private val _whenTerminated = Promise[HttpTerminated]()

    /**
     * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
     * [[pekko.stream.scaladsl.Source]]
     *
     * Note that unbinding does NOT terminate existing connections.
     * Unbinding only means that the server will not accept new connections,
     * and existing connections are allowed to still perform request/response cycles.
     * This can be useful when one wants to let clients finish whichever work they have remaining,
     * while signalling them using some other way that the server will be terminating soon -- e.g.
     * by sending such information in the still being sent out responses, such that the client can
     * switch to a new server when it is ready.
     *
     * Alternatively you may want to use the [[terminate]] method which unbinds and performs
     * some level of gracefully replying with
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the unbinding has been completed.
     *
     * Note: rather than unbinding explicitly you can also use [[addToCoordinatedShutdown]] to add this task to Akka's coordinated shutdown.
     */
    def unbind(): Future[Done] =
      unbindAction().map(_ => Done)(ExecutionContexts.sameThreadExecutionContext)

    /**
     * Triggers "graceful" termination request being handled on this connection.
     *
     * Termination works as follows:
     *
     * 1) Unbind:
     * - the server port is unbound; no new connections will be accepted.
     *
     * 1.5) Immediately the ServerBinding `whenTerminationSignalIssued` future is completed.
     * This can be used to signal parts of the application that the http server is shutting down and they should clean up as well.
     * Note also that for more advanced shut down scenarios you may want to use the Coordinated Shutdown capabilities of Akka.
     *
     * 2) if a connection has no "in-flight" request, it is terminated immediately
     *
     * 3) Handle in-flight request:
     * - if a request is "in-flight" (being handled by user code), it is given `hardDeadline` time to complete,
     *   - if user code emits a response within the timeout, then this response is sent to the client with a `Connection: close` header and the connection is closed.
     *     - however if it is a streaming response, it is also mandated that it shall complete within the deadline, and if it does not
     *       the connection will be terminated regardless of status of the streaming response (this is because such response could be infinite,
     *       which could trap the server in a situation where it could not terminate if it were to wait for a response to "finish")
     *     - existing streaming responses must complete before the deadline as well.
     *       When the deadline is reached the connection will be terminated regardless of status of the streaming responses.
     *   - if user code does not reply with a response within the deadline we produce a special [[pekko.http.javadsl.settings.ServerSettings.getTerminationDeadlineExceededResponse]]
     *     HTTP response (e.g. 503 Service Unavailable)
     *
     * 4) Keep draining incoming requests on existing connection:
     * - The existing connection will remain alive for until the `hardDeadline` is exceeded,
     *   yet no new requests will be delivered to the user handler. All such drained responses will be replied to with an
     *   termination response (as explained in phase 3).
     *
     * 5) Close still existing connections
     * - Connections are terminated forcefully once the `hardDeadline` is exceeded.
     *   The `whenTerminated` future is completed as well, so the graceful termination (of the `ActorSystem` or entire JVM
     *   itself can be safely performed, as by then it is known that no connections remain alive to this server).
     *
     * Note that the termination response is configurable in [[pekko.http.javadsl.settings.ServerSettings]], and by default is an `503 Service Unavailable`,
     * with an empty response entity.
     *
     * Note: rather than terminating explicitly you can also use [[addToCoordinatedShutdown]] to add this task to Akka's coordinated shutdown.
     *
     * @param hardDeadline timeout after which all requests and connections shall be forcefully terminated
     * @return future which completes successfully with a marker object once all connections have been terminated
     */
    def terminate(hardDeadline: FiniteDuration): Future[HttpTerminated] = {
      require(hardDeadline > Duration.Zero, "deadline must be greater than 0, was: " + hardDeadline)

      _whenTerminationSignalIssued.trySuccess(hardDeadline.fromNow)
      val terminated =
        unbindAction().flatMap(_ => terminateAction(hardDeadline))(ExecutionContexts.sameThreadExecutionContext)
      _whenTerminated.completeWith(terminated)
      whenTerminated
    }

    /**
     * Completes when the [[terminate]] is called and server termination is in progress.
     * Can be useful to make parts of your application aware that termination has been issued,
     * and they have [[Deadline]] time remaining to clean-up before the server will forcefully close
     * existing connections.
     *
     * Note that while termination is in progress, no new connections will be accepted (i.e. termination implies prior [[unbind]]).
     */
    def whenTerminationSignalIssued: Future[Deadline] =
      _whenTerminationSignalIssued.future

    /**
     * This future completes when the termination process, as initiated by an [[terminate]] call has completed.
     * This means that the server is by then: unbound, and has closed all existing connections.
     *
     * This signal can for example be used to safely terminate the underlying ActorSystem.
     *
     * Note that this signal may be used for Coordinated Shutdown to proceed to next steps in the shutdown.
     * You may also explicitly depend on this future to perform your next shutting down steps.
     */
    def whenTerminated: Future[HttpTerminated] =
      _whenTerminated.future

    /**
     * Adds this `ServerBinding` to the actor system's coordinated shutdown, so that [[unbind]] and [[terminate]] get
     * called appropriately before the system is shut down.
     *
     * @param hardTerminationDeadline timeout after which all requests and connections shall be forcefully terminated
     */
    def addToCoordinatedShutdown(hardTerminationDeadline: FiniteDuration)(
        implicit system: ClassicActorSystemProvider): ServerBinding = {
      val shutdown = CoordinatedShutdown(system)
      shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, s"http-unbind-${localAddress}") { () =>
        unbind()
      }
      shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, s"http-terminate-${localAddress}") { () =>
        terminate(hardTerminationDeadline).map(_ => Done)(ExecutionContexts.sameThreadExecutionContext)
      }
      this
    }
  }

  /** Type used to carry meaningful information when server termination has completed successfully. */
  @DoNotInherit sealed abstract class HttpTerminated extends pekko.http.javadsl.HttpTerminated
  sealed abstract class HttpServerTerminated extends HttpTerminated
  object HttpServerTerminated extends HttpServerTerminated
  sealed abstract class HttpConnectionTerminated extends HttpTerminated
  object HttpConnectionTerminated extends HttpConnectionTerminated

  /**
   * Represents one accepted incoming HTTP connection.
   */
  final case class IncomingConnection(
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      _flow: Flow[HttpResponse, HttpRequest, ServerTerminator]) {

    def flow: Flow[HttpResponse, HttpRequest, NotUsed] = _flow.mapMaterializedValue(_ => NotUsed)

    /**
     * Handles the connection with the given flow, which is materialized exactly once
     * and the respective materialization result returned.
     */
    def handleWith[Mat](handler: Flow[HttpRequest, HttpResponse, Mat])(implicit fm: Materializer): Mat =
      flow.joinMat(handler)(Keep.right).run()

    /**
     * Handles the connection with the given handler function.
     */
    def handleWithSyncHandler(handler: HttpRequest => HttpResponse)(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].map(handler))

    /**
     * Handles the connection with the given handler function.
     */
    def handleWithAsyncHandler(handler: HttpRequest => Future[HttpResponse], parallelism: Int = 1)(
        implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].mapAsync(parallelism)(handler))
  }

  /**
   * Represents a prospective outgoing HTTP connection.
   */
  final case class OutgoingConnection(localAddress: InetSocketAddress, remoteAddress: InetSocketAddress)

  /**
   * Represents a connection pool to a specific target host and pool configuration.
   *
   * Not for user extension.
   */
  @DoNotInherit
  sealed abstract class HostConnectionPool extends Product {
    def setup: HostConnectionPoolSetup

    /**
     * Asynchronously triggers the shutdown of the host connection pool.
     *
     * The produced [[scala.concurrent.Future]] is fulfilled when the shutdown has been completed.
     */
    def shutdown(): Future[Done]

    private[http] def toJava = new pekko.http.javadsl.HostConnectionPool {
      override def setup = HostConnectionPool.this.setup
      def shutdown(): CompletionStage[Done] = HostConnectionPool.this.shutdown().toJava
    }

    override def productArity: Int = 1
    override def productElement(n: Int): Any = if (n == 0) setup else throw new IllegalArgumentException
    override def canEqual(that: Any): Boolean = that.isInstanceOf[HostConnectionPool]
  }
  @deprecated("Not needed any more. Kept for binary compatibility.", "10.2.0")
  private[http] object HostConnectionPool

  /** INTERNAL API */
  @InternalApi
  final private[http] class HostConnectionPoolImpl(val poolId: PoolId, master: PoolMaster) extends HostConnectionPool {
    override def setup: HostConnectionPoolSetup = poolId.hcps
    override def shutdown(): Future[Done] = master.shutdown(poolId)

    override def equals(obj: Any): Boolean = obj match {
      case i: HostConnectionPoolImpl if i.poolId == poolId => true
      case _                                               => false
    }
  }

  //////////////////// EXTENSION SETUP ///////////////////

  def apply()(implicit system: ClassicActorSystemProvider): HttpExt = super.apply(system)
  override def apply(system: ActorSystem): HttpExt = super.apply(system)

  def lookup() = Http

  def createExtension(system: ExtendedActorSystem): HttpExt =
    new HttpExt(system.settings.config.getConfig("pekko.http"))(system)

  @nowarn("msg=use remote-address-attribute instead")
  @InternalApi
  private[pekko] def prepareAttributes(settings: ServerSettings, incoming: Tcp.IncomingConnection) =
    if (settings.remoteAddressHeader || settings.remoteAddressAttribute)
      HttpAttributes.remoteAddress(incoming.remoteAddress)
    else HttpAttributes.empty

  @InternalApi
  private[http] def cancellationStrategyAttributeForDelay(delay: FiniteDuration): Attributes =
    Attributes(CancellationStrategy {
      delay match {
        case Duration.Zero => FailStage
        case d             => AfterDelay(d, FailStage)
      }
    })
}

/**
 * TLS configuration for an HTTPS server binding or client connection.
 * For the sslContext please refer to the com.typeasfe.ssl-config library.
 * The remaining four parameters configure the initial session that will
 * be negotiated, see [[pekko.stream.TLSProtocol.NegotiateNewSession]] for details.
 */
@deprecated("use ConnectionContext.httpsServer and httpsClient directly", since = "10.2.0")
trait DefaultSSLContextCreation {

  protected def system: ActorSystem
  def sslConfig = PekkoSSLConfig(system)

  // --- log warnings ---
  private[this] def log = system.log

  @deprecated("PekkoSSLConfig usage is deprecated", since = "10.2.0")
  def validateAndWarnAboutLooseSettings() = ()
  // --- end of log warnings ---

  @deprecated("use ConnectionContext.httpServer instead", since = "10.2.0")
  def createDefaultClientHttpsContext(): HttpsConnectionContext =
    createClientHttpsContext(PekkoSSLConfig(system))

  @deprecated("use ConnectionContext.httpServer instead", since = "10.2.0")
  def createServerHttpsContext(sslConfig: PekkoSSLConfig): HttpsConnectionContext = {
    log.warning("Automatic server-side configuration is not supported yet, will attempt to use client-side settings. " +
      "Instead it is recommended to construct the Servers HttpsConnectionContext manually (via SSLContext).")
    createClientHttpsContext(sslConfig)
  }

  @deprecated("use ConnectionContext.httpClient(sslContext) instead", since = "10.2.0")
  def createClientHttpsContext(sslConfig: PekkoSSLConfig): HttpsConnectionContext = {
    val config = sslConfig.config

    val log = Logging(system, getClass)
    val mkLogger = new PekkoLoggerFactory(system)

    // initial ssl context!
    val sslContext = if (sslConfig.config.default) {
      log.debug("buildSSLContext: ssl-config.default is true, using default SSLContext")
      sslConfig.validateDefaultTrustManager(config)
      SSLContext.getDefault
    } else {
      // break out the static methods as much as we can...
      val keyManagerFactory = sslConfig.buildKeyManagerFactory(config)
      val trustManagerFactory = sslConfig.buildTrustManagerFactory(config)
      new ConfigSSLContextBuilder(mkLogger, config, keyManagerFactory, trustManagerFactory).build()
    }

    // protocols!
    val defaultParams = sslContext.getDefaultSSLParameters
    val defaultProtocols = defaultParams.getProtocols
    val protocols = sslConfig.configureProtocols(defaultProtocols, config)
    defaultParams.setProtocols(protocols)

    // ciphers!
    val defaultCiphers = defaultParams.getCipherSuites
    val cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, config)
    defaultParams.setCipherSuites(cipherSuites)

    // auth!
    import com.typesafe.sslconfig.ssl.{ ClientAuth => SslClientAuth }
    val clientAuth = config.sslParametersConfig.clientAuth match {
      case SslClientAuth.Default => None
      case SslClientAuth.Want    => Some(TLSClientAuth.Want)
      case SslClientAuth.Need    => Some(TLSClientAuth.Need)
      case SslClientAuth.None    => Some(TLSClientAuth.None)
    }

    // hostname!
    if (!sslConfig.config.loose.disableHostnameVerification) {
      defaultParams.setEndpointIdentificationAlgorithm("https")
    }

    new HttpsConnectionContext(
      sslContext,
      Some(sslConfig),
      Some(cipherSuites.toList),
      Some(defaultProtocols.toList),
      clientAuth,
      Some(defaultParams))
  }

}
