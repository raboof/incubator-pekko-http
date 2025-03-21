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

import java.util.Optional

import org.apache.pekko
import pekko.actor.{ ActorSystem, ClassicActorSystemProvider }
import pekko.http.impl.engine.parsing.BodyPartParser
import pekko.http.impl.settings.ParserSettingsImpl
import java.{ util => ju }

import pekko.annotation.DoNotInherit
import pekko.http.impl.util.JavaMapping.Implicits._

import scala.annotation.varargs
import scala.collection.JavaConverters._
import pekko.http.javadsl.model.{ HttpMethod, MediaType, StatusCode, Uri }
import scala.annotation.nowarn
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ParserSettings private[pekko] () extends BodyPartParser.Settings { self: ParserSettingsImpl =>
  def getMaxUriLength: Int
  def getMaxMethodLength: Int
  def getMaxResponseReasonLength: Int
  def getMaxHeaderNameLength: Int
  def getMaxHeaderValueLength: Int
  def getMaxHeaderCount: Int
  def getMaxContentLength: Long
  def getMaxToStrictBytes: Long
  def getMaxChunkExtLength: Int
  def getMaxChunkSize: Int
  def getMaxCommentParsingDepth: Int
  def getUriParsingMode: Uri.ParsingMode
  def getCookieParsingMode: ParserSettings.CookieParsingMode
  def getIllegalHeaderWarnings: Boolean
  def getIgnoreIllegalHeaderFor: Set[String]
  def getErrorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity
  def getIllegalResponseHeaderNameProcessingMode: ParserSettings.IllegalResponseHeaderNameProcessingMode
  def getIllegalResponseHeaderValueProcessingMode: ParserSettings.IllegalResponseHeaderValueProcessingMode
  def getConflictingContentTypeHeaderProcessingMode: ParserSettings.ConflictingContentTypeHeaderProcessingMode
  def getHeaderValueCacheLimits: ju.Map[String, Int]
  def getIncludeTlsSessionInfoHeader: Boolean
  def getIncludeSslSessionAttribute: Boolean
  def headerValueCacheLimits: Map[String, Int]
  def getCustomMethods: java.util.function.Function[String, Optional[HttpMethod]]
  def getCustomStatusCodes: java.util.function.Function[Int, Optional[StatusCode]]
  def getCustomMediaTypes: pekko.japi.function.Function2[String, String, Optional[MediaType]]
  def getModeledHeaderParsing: Boolean

  // ---

  def withMaxUriLength(newValue: Int): ParserSettings = self.copy(maxUriLength = newValue)
  def withMaxMethodLength(newValue: Int): ParserSettings = self.copy(maxMethodLength = newValue)
  def withMaxResponseReasonLength(newValue: Int): ParserSettings = self.copy(maxResponseReasonLength = newValue)
  def withMaxHeaderNameLength(newValue: Int): ParserSettings = self.copy(maxHeaderNameLength = newValue)
  def withMaxHeaderValueLength(newValue: Int): ParserSettings = self.copy(maxHeaderValueLength = newValue)
  def withMaxHeaderCount(newValue: Int): ParserSettings = self.copy(maxHeaderCount = newValue)
  def withMaxContentLength(newValue: Long): ParserSettings = self.copy(maxContentLengthSetting = Some(newValue))
  def withMaxToStrictBytes(newValue: Long): ParserSettings = self.copy(maxToStrictBytes = newValue)
  def withMaxChunkExtLength(newValue: Int): ParserSettings = self.copy(maxChunkExtLength = newValue)
  def withMaxChunkSize(newValue: Int): ParserSettings = self.copy(maxChunkSize = newValue)
  def withMaxCommentParsingDepth(newValue: Int): ParserSettings = self.copy(maxCommentParsingDepth = newValue)
  def withUriParsingMode(newValue: Uri.ParsingMode): ParserSettings = self.copy(uriParsingMode = newValue.asScala)
  def withCookieParsingMode(newValue: ParserSettings.CookieParsingMode): ParserSettings =
    self.copy(cookieParsingMode = newValue.asScala)
  def withIllegalHeaderWarnings(newValue: Boolean): ParserSettings = self.copy(illegalHeaderWarnings = newValue)
  def withErrorLoggingVerbosity(newValue: ParserSettings.ErrorLoggingVerbosity): ParserSettings =
    self.copy(errorLoggingVerbosity = newValue.asScala)
  def withHeaderValueCacheLimits(newValue: ju.Map[String, Int]): ParserSettings =
    self.copy(headerValueCacheLimits = newValue.asScala.toMap)
  def withIncludeTlsSessionInfoHeader(newValue: Boolean): ParserSettings =
    self.copy(includeTlsSessionInfoHeader = newValue)
  def withIncludeSslSessionAttribute(newValue: Boolean): ParserSettings =
    self.copy(includeSslSessionAttribute = newValue)
  def withModeledHeaderParsing(newValue: Boolean): ParserSettings = self.copy(modeledHeaderParsing = newValue)
  def withIgnoreIllegalHeaderFor(newValue: List[String]): ParserSettings =
    self.copy(ignoreIllegalHeaderFor = newValue.map(_.toLowerCase).toSet)

  // special ---

  @varargs
  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m => m.name -> m.asScala).toMap
    self.copy(customMethods = map.get)
  }
  @varargs
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c => c.intValue -> c.asScala).toMap
    self.copy(customStatusCodes = map.get)
  }
  @varargs
  def withCustomMediaTypes(mediaTypes: MediaType*): ParserSettings = {
    val map = mediaTypes.map(c => (c.mainType, c.subType) -> c.asScala).toMap
    self.copy(customMediaTypes = (main, sub) => map.get(main -> sub))
  }

}

object ParserSettings extends SettingsCompanion[ParserSettings] {
  trait CookieParsingMode
  trait ErrorLoggingVerbosity
  trait IllegalResponseHeaderNameProcessingMode
  trait IllegalResponseHeaderValueProcessingMode
  trait ConflictingContentTypeHeaderProcessingMode

  /**
   * @deprecated Use forServer or forClient instead.
   */
  @Deprecated
  @deprecated("Use forServer or forClient instead", since = "Akka HTTP 10.2.0")
  override def create(config: Config): ParserSettings = ParserSettingsImpl(config)

  /**
   * @deprecated Use forServer or forClient instead.
   */
  @Deprecated
  @deprecated("Use forServer or forClient instead", since = "Akka HTTP 10.2.0")
  override def create(configOverrides: String): ParserSettings = ParserSettingsImpl(configOverrides)

  /**
   * @deprecated Use forServer or forClient instead.
   */
  @Deprecated
  @deprecated("Use forServer or forClient instead", since = "Akka HTTP 10.2.0")
  @nowarn("msg=create overrides concrete, non-deprecated symbol")
  override def create(system: ActorSystem): ParserSettings = create(system.settings.config)

  def forServer(system: ClassicActorSystemProvider): ParserSettings =
    pekko.http.scaladsl.settings.ParserSettings.forServer(system)
  def forClient(system: ClassicActorSystemProvider): ParserSettings =
    pekko.http.scaladsl.settings.ParserSettings.forClient(system)
}
