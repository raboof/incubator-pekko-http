/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.javadsl.settings

import java.time.{ Duration => JDuration }

import com.typesafe.config.Config

import scala.concurrent.duration.{ Duration, FiniteDuration }

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.{ ApiMayChange, DoNotInherit }
import pekko.http.impl.settings.ConnectionPoolSettingsImpl
import pekko.http.impl.util.JavaMapping.Implicits._
import pekko.http.javadsl.ClientTransport
import pekko.util.JavaDurationConverters._

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ConnectionPoolSettings private[pekko] () { self: ConnectionPoolSettingsImpl =>
  def getMaxConnections: Int = maxConnections
  def getMinConnections: Int = minConnections
  def getMaxRetries: Int = maxRetries
  def getMaxOpenRequests: Int = maxOpenRequests
  def getPipeliningLimit: Int = pipeliningLimit
  def getMaxConnectionLifetime: JDuration = maxConnectionLifetime.asJava
  def getBaseConnectionBackoff: FiniteDuration = baseConnectionBackoff
  def getMaxConnectionBackoff: FiniteDuration = maxConnectionBackoff
  def getIdleTimeout: Duration = idleTimeout
  def getKeepAliveTimeout: Duration = keepAliveTimeout
  def getConnectionSettings: ClientConnectionSettings = connectionSettings

  @ApiMayChange
  def getResponseEntitySubscriptionTimeout: Duration = responseEntitySubscriptionTimeout

  // ---

  @ApiMayChange
  def withHostOverrides(hostOverrides: java.util.List[(String, ConnectionPoolSettings)]): ConnectionPoolSettings = {
    import scala.collection.JavaConverters._
    self.copy(hostOverrides = hostOverrides.asScala.toList.map { case (h, s) =>
      ConnectionPoolSettingsImpl.hostRegex(h) -> s.asScala
    })
  }

  @ApiMayChange
  def appendHostOverride(hostPattern: String, settings: ConnectionPoolSettings): ConnectionPoolSettings =
    self.copy(hostOverrides = hostOverrides :+ (ConnectionPoolSettingsImpl.hostRegex(hostPattern) -> settings.asScala))

  def withMaxConnections(n: Int): ConnectionPoolSettings
  def withMinConnections(n: Int): ConnectionPoolSettings
  def withMaxRetries(n: Int): ConnectionPoolSettings
  def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings

  /** Client-side pipelining is not currently supported, see https://github.com/apache/incubator-pekko-http/issues/32 */
  def withPipeliningLimit(newValue: Int): ConnectionPoolSettings
  def withBaseConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings
  def withMaxConnectionBackoff(newValue: FiniteDuration): ConnectionPoolSettings
  def withIdleTimeout(newValue: Duration): ConnectionPoolSettings
  def withKeepAliveTimeout(newValue: Duration): ConnectionPoolSettings
  def withMaxConnectionLifetime(newValue: Duration): ConnectionPoolSettings
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings =
    self.copyDeep(_.withConnectionSettings(newValue.asScala), connectionSettings = newValue.asScala)

  @ApiMayChange
  def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings

  def withTransport(newValue: ClientTransport): ConnectionPoolSettings =
    withUpdatedConnectionSettings(_.withTransport(newValue.asScala))
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettingsImpl(config)
  override def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ConnectionPoolSettings = create(system.settings.config)
}
