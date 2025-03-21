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

package org.apache.pekko.http.caching.scaladsl

import org.apache.pekko
import pekko.annotation.DoNotInherit
import pekko.http.caching.impl.settings.LfuCachingSettingsImpl
import pekko.http.caching.javadsl
import pekko.http.scaladsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings private[http] () extends javadsl.LfuCacheSettings { self: LfuCachingSettingsImpl =>
  def maxCapacity: Int
  def initialCapacity: Int
  def timeToLive: Duration
  def timeToIdle: Duration

  final def getMaxCapacity: Int = maxCapacity
  final def getInitialCapacity: Int = initialCapacity
  final def getTimeToLive: Duration = timeToLive
  final def getTimeToIdle: Duration = timeToIdle

  override def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  override def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings =
    self.copy(initialCapacity = newInitialCapacity)
  override def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings = self.copy(timeToLive = newTimeToLive)
  override def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings = self.copy(timeToIdle = newTimeToIdle)
}

object LfuCacheSettings extends SettingsCompanion[LfuCacheSettings] {
  def apply(config: Config): LfuCacheSettings = LfuCachingSettingsImpl(config)
  def apply(configOverrides: String): LfuCacheSettings = LfuCachingSettingsImpl(configOverrides)
}
