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

package org.apache.pekko.http.caching.javadsl

import org.apache.pekko
import pekko.annotation.DoNotInherit
import pekko.http.caching.impl.settings.LfuCachingSettingsImpl
import pekko.http.javadsl.settings.SettingsCompanion
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class LfuCacheSettings private[http] () { self: LfuCachingSettingsImpl =>

  /* JAVA APIs */
  def getMaxCapacity: Int
  def getInitialCapacity: Int
  def getTimeToLive: Duration
  def getTimeToIdle: Duration

  def withMaxCapacity(newMaxCapacity: Int): LfuCacheSettings = self.copy(maxCapacity = newMaxCapacity)
  def withInitialCapacity(newInitialCapacity: Int): LfuCacheSettings = self.copy(initialCapacity = newInitialCapacity)
  def withTimeToLive(newTimeToLive: Duration): LfuCacheSettings = self.copy(timeToLive = newTimeToLive)
  def withTimeToIdle(newTimeToIdle: Duration): LfuCacheSettings = self.copy(timeToIdle = newTimeToIdle)
}

object LfuCacheSettings extends SettingsCompanion[LfuCacheSettings] {
  def create(config: Config): LfuCacheSettings = LfuCachingSettingsImpl(config)
  def create(configOverrides: String): LfuCacheSettings = LfuCachingSettingsImpl(configOverrides)
}
