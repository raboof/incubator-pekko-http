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

package org.apache.pekko.http.scaladsl.settings

import java.util
import java.util.Optional
import java.util.function.Function

import org.apache.pekko
import pekko.actor.ClassicActorSystemProvider
import pekko.annotation.DoNotInherit
import pekko.http.impl.settings.ParserSettingsImpl
import pekko.http.impl.util._
import pekko.http.javadsl.model
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.{ settings => js }
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ParserSettings private[pekko] () extends pekko.http.javadsl.settings.ParserSettings {
  self: ParserSettingsImpl =>
  def maxUriLength: Int
  def maxMethodLength: Int
  def maxResponseReasonLength: Int
  def maxHeaderNameLength: Int
  def maxHeaderValueLength: Int
  def maxHeaderCount: Int
  def maxContentLength: Long
  def maxToStrictBytes: Long
  def maxChunkExtLength: Int
  def maxChunkSize: Int
  def maxCommentParsingDepth: Int
  def uriParsingMode: Uri.ParsingMode
  def cookieParsingMode: ParserSettings.CookieParsingMode
  def illegalHeaderWarnings: Boolean
  def ignoreIllegalHeaderFor: Set[String]
  def errorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity
  def illegalResponseHeaderNameProcessingMode: ParserSettings.IllegalResponseHeaderNameProcessingMode
  def illegalResponseHeaderValueProcessingMode: ParserSettings.IllegalResponseHeaderValueProcessingMode
  def conflictingContentTypeHeaderProcessingMode: ParserSettings.ConflictingContentTypeHeaderProcessingMode
  def headerValueCacheLimits: Map[String, Int]
  def includeTlsSessionInfoHeader: Boolean
  def includeSslSessionAttribute: Boolean
  def customMethods: String => Option[HttpMethod]
  def customStatusCodes: Int => Option[StatusCode]
  def customMediaTypes: MediaTypes.FindCustom
  def modeledHeaderParsing: Boolean

  /* Java APIs */
  override def getCookieParsingMode: js.ParserSettings.CookieParsingMode = cookieParsingMode
  override def getHeaderValueCacheLimits: util.Map[String, Int] = headerValueCacheLimits.asJava
  override def getMaxChunkExtLength = maxChunkExtLength
  override def getUriParsingMode: pekko.http.javadsl.model.Uri.ParsingMode = uriParsingMode
  override def getMaxHeaderCount = maxHeaderCount
  override def getMaxContentLength = maxContentLength
  override def getMaxToStrictBytes = maxToStrictBytes
  override def getMaxHeaderValueLength = maxHeaderValueLength
  override def getIncludeTlsSessionInfoHeader = includeTlsSessionInfoHeader
  override def getIncludeSslSessionAttribute = includeSslSessionAttribute
  override def getIllegalHeaderWarnings = illegalHeaderWarnings
  override def getIgnoreIllegalHeaderFor = ignoreIllegalHeaderFor
  override def getMaxHeaderNameLength = maxHeaderNameLength
  override def getMaxChunkSize = maxChunkSize
  override def getMaxResponseReasonLength = maxResponseReasonLength
  override def getMaxUriLength = maxUriLength
  override def getMaxMethodLength = maxMethodLength
  override def getMaxCommentParsingDepth: Int = maxCommentParsingDepth
  override def getErrorLoggingVerbosity: js.ParserSettings.ErrorLoggingVerbosity = errorLoggingVerbosity
  override def getIllegalResponseHeaderNameProcessingMode = illegalResponseHeaderNameProcessingMode
  override def getIllegalResponseHeaderValueProcessingMode = illegalResponseHeaderValueProcessingMode
  override def getConflictingContentTypeHeaderProcessingMode = conflictingContentTypeHeaderProcessingMode

  override def getCustomMethods = new Function[String, Optional[pekko.http.javadsl.model.HttpMethod]] {
    override def apply(t: String) = OptionConverters.toJava(customMethods(t))
  }
  override def getCustomStatusCodes = new Function[Int, Optional[pekko.http.javadsl.model.StatusCode]] {
    override def apply(t: Int) = OptionConverters.toJava(customStatusCodes(t))
  }
  override def getCustomMediaTypes =
    new pekko.japi.function.Function2[String, String, Optional[pekko.http.javadsl.model.MediaType]] {
      override def apply(mainType: String, subType: String): Optional[model.MediaType] =
        OptionConverters.toJava(customMediaTypes(mainType, subType))
    }
  def getModeledHeaderParsing: Boolean = modeledHeaderParsing

  // override for more specific return type
  override def withMaxUriLength(newValue: Int): ParserSettings = self.copy(maxUriLength = newValue)
  override def withMaxMethodLength(newValue: Int): ParserSettings = self.copy(maxMethodLength = newValue)
  override def withMaxResponseReasonLength(newValue: Int): ParserSettings =
    self.copy(maxResponseReasonLength = newValue)
  override def withMaxHeaderNameLength(newValue: Int): ParserSettings = self.copy(maxHeaderNameLength = newValue)
  override def withMaxHeaderValueLength(newValue: Int): ParserSettings = self.copy(maxHeaderValueLength = newValue)
  override def withMaxHeaderCount(newValue: Int): ParserSettings = self.copy(maxHeaderCount = newValue)
  override def withMaxContentLength(newValue: Long): ParserSettings =
    self.copy(maxContentLengthSetting = Some(newValue))
  def withMaxContentLength(newValue: Option[Long]): ParserSettings = self.copy(maxContentLengthSetting = newValue)
  override def withMaxToStrictBytes(newValue: Long): ParserSettings = self.copy(maxToStrictBytes = newValue)
  override def withMaxChunkExtLength(newValue: Int): ParserSettings = self.copy(maxChunkExtLength = newValue)
  override def withMaxChunkSize(newValue: Int): ParserSettings = self.copy(maxChunkSize = newValue)
  override def withMaxCommentParsingDepth(newValue: Int): ParserSettings = self.copy(maxCommentParsingDepth = newValue)
  override def withIllegalHeaderWarnings(newValue: Boolean): ParserSettings =
    self.copy(illegalHeaderWarnings = newValue)
  override def withIncludeTlsSessionInfoHeader(newValue: Boolean): ParserSettings =
    self.copy(includeTlsSessionInfoHeader = newValue)
  override def withIncludeSslSessionAttribute(newValue: Boolean): ParserSettings =
    self.copy(includeSslSessionAttribute = newValue)
  override def withModeledHeaderParsing(newValue: Boolean): ParserSettings = self.copy(modeledHeaderParsing = newValue)
  override def withIgnoreIllegalHeaderFor(newValue: List[String]): ParserSettings =
    self.copy(ignoreIllegalHeaderFor = newValue.map(_.toLowerCase).toSet)

  // overloads for idiomatic Scala use
  def withUriParsingMode(newValue: Uri.ParsingMode): ParserSettings = self.copy(uriParsingMode = newValue)
  def withCookieParsingMode(newValue: ParserSettings.CookieParsingMode): ParserSettings =
    self.copy(cookieParsingMode = newValue)
  def withErrorLoggingVerbosity(newValue: ParserSettings.ErrorLoggingVerbosity): ParserSettings =
    self.copy(errorLoggingVerbosity = newValue)
  def withHeaderValueCacheLimits(newValue: Map[String, Int]): ParserSettings =
    self.copy(headerValueCacheLimits = newValue)
  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m => m.name -> m).toMap
    self.copy(customMethods = map.get)
  }
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c => c.intValue -> c).toMap
    self.copy(customStatusCodes = map.get)
  }
  def withCustomMediaTypes(types: MediaType*): ParserSettings = {
    val map = types.map(c => (c.mainType, c.subType) -> c).toMap
    self.copy(customMediaTypes = (main, sub) => map.get((main, sub)))
  }
  def withIllegalResponseHeaderNameProcessingMode(
      newValue: ParserSettings.IllegalResponseHeaderNameProcessingMode): ParserSettings =
    self.copy(illegalResponseHeaderNameProcessingMode = newValue)
  def withIllegalResponseHeaderValueProcessingMode(
      newValue: ParserSettings.IllegalResponseHeaderValueProcessingMode): ParserSettings =
    self.copy(illegalResponseHeaderValueProcessingMode = newValue)
  def withConflictingContentTypeHeaderProcessingMode(
      newValue: ParserSettings.ConflictingContentTypeHeaderProcessingMode): ParserSettings =
    self.copy(conflictingContentTypeHeaderProcessingMode = newValue)
}

object ParserSettings extends SettingsCompanion[ParserSettings] {
  sealed trait CookieParsingMode extends pekko.http.javadsl.settings.ParserSettings.CookieParsingMode
  object CookieParsingMode {
    case object RFC6265 extends CookieParsingMode
    case object Raw extends CookieParsingMode

    def apply(mode: String): CookieParsingMode = mode.toRootLowerCase match {
      case "rfc6265" => RFC6265
      case "raw"     => Raw
    }
  }

  sealed trait ErrorLoggingVerbosity extends pekko.http.javadsl.settings.ParserSettings.ErrorLoggingVerbosity
  object ErrorLoggingVerbosity {
    case object Off extends ErrorLoggingVerbosity
    case object Simple extends ErrorLoggingVerbosity
    case object Full extends ErrorLoggingVerbosity

    def apply(string: String): ErrorLoggingVerbosity =
      string.toRootLowerCase match {
        case "off"    => Off
        case "simple" => Simple
        case "full"   => Full
        case x        => throw new IllegalArgumentException(s"[$x] is not a legal `error-logging-verbosity` setting")
      }
  }

  sealed trait IllegalResponseHeaderValueProcessingMode
      extends pekko.http.javadsl.settings.ParserSettings.IllegalResponseHeaderValueProcessingMode
  object IllegalResponseHeaderValueProcessingMode {
    case object Error extends IllegalResponseHeaderValueProcessingMode
    case object Warn extends IllegalResponseHeaderValueProcessingMode
    case object Ignore extends IllegalResponseHeaderValueProcessingMode

    def apply(string: String): IllegalResponseHeaderValueProcessingMode =
      string.toRootLowerCase match {
        case "error"  => Error
        case "warn"   => Warn
        case "ignore" => Ignore
        case x => throw new IllegalArgumentException(
            s"[$x] is not a legal `illegal-response-header-value-processing-mode` setting")
      }
  }

  sealed trait IllegalResponseHeaderNameProcessingMode
      extends pekko.http.javadsl.settings.ParserSettings.IllegalResponseHeaderNameProcessingMode
  object IllegalResponseHeaderNameProcessingMode {
    case object Error extends IllegalResponseHeaderNameProcessingMode
    case object Warn extends IllegalResponseHeaderNameProcessingMode
    case object Ignore extends IllegalResponseHeaderNameProcessingMode

    def apply(string: String): IllegalResponseHeaderNameProcessingMode =
      string.toRootLowerCase match {
        case "error"  => Error
        case "warn"   => Warn
        case "ignore" => Ignore
        case x => throw new IllegalArgumentException(
            s"[$x] is not a legal `illegal-response-header-name-processing-mode` setting")
      }
  }

  sealed trait ConflictingContentTypeHeaderProcessingMode
      extends pekko.http.javadsl.settings.ParserSettings.ConflictingContentTypeHeaderProcessingMode
  object ConflictingContentTypeHeaderProcessingMode {
    case object Error extends ConflictingContentTypeHeaderProcessingMode
    case object First extends ConflictingContentTypeHeaderProcessingMode
    case object Last extends ConflictingContentTypeHeaderProcessingMode
    case object NoContentType extends ConflictingContentTypeHeaderProcessingMode

    def apply(string: String): ConflictingContentTypeHeaderProcessingMode =
      string.toRootLowerCase match {
        case "error"           => Error
        case "first"           => First
        case "last"            => Last
        case "no-content-type" => NoContentType
        case x => throw new IllegalArgumentException(
            s"[$x] is not a legal `conflicting-content-type-header-processing-mode` setting")
      }
  }

  @deprecated("Use forServer or forClient instead", "Akka HTTP 10.2.0")
  override def apply(config: Config): ParserSettings = ParserSettingsImpl(config)
  @deprecated("Use forServer or forClient instead", "Akka HTTP 10.2.0")
  override def apply(configOverrides: String): ParserSettings = ParserSettingsImpl(configOverrides)

  def forServer(implicit system: ClassicActorSystemProvider): ParserSettings =
    ParserSettingsImpl.forServer(system.classicSystem.settings.config)
  def forClient(implicit system: ClassicActorSystemProvider): ParserSettings =
    ParserSettingsImpl.fromSubConfig(system.classicSystem.settings.config,
      system.classicSystem.settings.config.getConfig("pekko.http.client.parsing"))
}
