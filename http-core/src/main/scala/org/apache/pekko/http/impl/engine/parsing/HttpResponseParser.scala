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

package org.apache.pekko.http.impl.engine.parsing

import javax.net.ssl.SSLSession
import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.util.control.{ NoStackTrace, NonFatal }
import org.apache.pekko
import pekko.http.scaladsl.settings.ParserSettings
import pekko.http.impl.model.parser.CharacterClasses
import pekko.util.ByteString
import pekko.http.scaladsl.model.{ ParsingException => _, _ }
import pekko.http.scaladsl.model.headers._
import ParserOutput._
import pekko.annotation.InternalApi
import pekko.http.impl.util.LogByteStringTools
import pekko.stream.scaladsl.Source

/**
 * INTERNAL API
 */
@InternalApi
private[http] class HttpResponseParser(protected val settings: ParserSettings,
    protected val headerParser: HttpHeaderParser)
    extends HttpMessageParser[ResponseOutput] { self =>
  import HttpResponseParser._
  import HttpMessageParser._
  import settings._

  private[this] var contextForCurrentResponse: Option[ResponseContext] = None
  private[this] var statusCode: StatusCode = StatusCodes.OK

  final override val isResponseParser = true

  final def createShallowCopy(): HttpResponseParser = new HttpResponseParser(settings, headerParser.createShallowCopy())

  final def setContextForNextResponse(responseContext: ResponseContext): Unit =
    if (contextForCurrentResponse.isEmpty) contextForCurrentResponse = Some(responseContext)

  final def onPull(): ResponseOutput = doPull()

  final def onUpstreamFinish(): Boolean = shouldComplete()

  override final def emit(output: ResponseOutput): Unit = {
    if (output == MessageEnd) contextForCurrentResponse = None
    super.emit(output)
  }

  override protected def parseMessage(input: ByteString, offset: Int): StateResult =
    if (contextForCurrentResponse.isDefined) {
      var cursor = parseProtocol(input, offset)
      if (byteChar(input, cursor) == ' ') {
        cursor = parseStatus(input, cursor + 1)
        parseHeaderLines(input, cursor)
      } else onBadProtocol(input.drop(cursor))
    } else {
      emit(NeedNextRequestMethod)
      continue(input, offset)(startNewMessage)
    }

  override final def onBadProtocol(input: ByteString) =
    throw new ParsingException(
      "The server-side protocol or HTTP version is not supported",
      s"start of response: [${LogByteStringTools.printByteString(input.take(16), 16, addPrefix = false, indent = "")}]")

  private def parseStatus(input: ByteString, cursor: Int): Int = {
    def badStatusCode() = throw new ParsingException("Illegal response status code")
    def badStatusCodeSpecific(code: Int) = throw new ParsingException("Illegal response status code: " + code)

    def parseStatusCode(reasonStartIdx: Int = -1, reasonEndIdx: Int = -1): Unit = {
      def intValue(offset: Int): Int = {
        val c = byteChar(input, cursor + offset)
        if (CharacterClasses.DIGIT(c)) c - '0' else badStatusCode()
      }
      val code = intValue(0) * 100 + intValue(1) * 10 + intValue(2)
      statusCode = code match {
        case 200 => StatusCodes.OK
        case code => StatusCodes.getForKey(code) match {
            case Some(x) => x
            case None => customStatusCodes(code).getOrElse {
                // A client must understand the class of any status code, as indicated by the first digit, and
                // treat an unrecognized status code as being equivalent to the x00 status code of that class
                // https://tools.ietf.org/html/rfc7231#section-6
                try {
                  val reason = asciiString(input, reasonStartIdx, reasonEndIdx)
                  StatusCodes.custom(code, reason)
                } catch {
                  case NonFatal(_) => badStatusCodeSpecific(code)
                }
              }
          }
      }
    }

    def isLF(idx: Int) = byteChar(input, idx) == '\n'
    def isCRLF(idx: Int) = byteChar(input, idx) == '\r' && isLF(idx + 1)
    def isNewLine(idx: Int) = isLF(idx) || isCRLF(idx)

    def skipNewLine(idx: Int) = {
      if (isCRLF(idx)) idx + 2
      else if (isLF(idx)) idx + 1
      else idx
    }

    if (byteChar(input, cursor + 3) == ' ') {
      val startIdx = cursor + 4
      @tailrec def scanNewLineIdx(idx: Int): Int =
        if (idx - startIdx <= maxResponseReasonLength)
          if (isNewLine(idx)) idx
          else scanNewLineIdx(idx + 1)
        else throw new ParsingException("Response reason phrase exceeds the configured limit of " +
          maxResponseReasonLength + " characters")
      val newLineIdx = scanNewLineIdx(startIdx)
      parseStatusCode(startIdx, newLineIdx)
      skipNewLine(newLineIdx)
    } else if (isNewLine(cursor + 3)) {
      parseStatusCode()
      // Status format with no reason phrase and no trailing space accepted, diverging from the spec
      // See https://github.com/apache/incubator-pekko-http/pull/989
      skipNewLine(cursor + 3)
    } else badStatusCode()
  }

  def handleInformationalResponses: Boolean = true

  // http://tools.ietf.org/html/rfc7230#section-3.3
  protected final def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
      clh: Option[`Content-Length`], cth: Option[`Content-Type`], isChunked: Boolean,
      expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean,
      sslSession: SSLSession): StateResult = {

    def emitResponseStart(
        createEntity: EntityCreator[ResponseOutput, ResponseEntity],
        headers: List[HttpHeader] = headers) = {

      val attributes: Map[AttributeKey[_], Any] =
        if (settings.includeSslSessionAttribute) Map(AttributeKeys.sslSession -> SslSessionInfo(sslSession))
        else Map.empty

      val close =
        contextForCurrentResponse.get.oneHundredContinueTrigger match {
          case None => closeAfterResponseCompletion
          case Some(trigger) if statusCode.isSuccess =>
            trigger.trySuccess(())
            closeAfterResponseCompletion
          case Some(trigger) =>
            trigger.tryFailure(OneHundredContinueError)
            true
        }
      emit(ResponseStart(statusCode, protocol, attributes, headers, createEntity, close))
    }

    def finishEmptyResponse() =
      statusCode match {
        case _: StatusCodes.Informational if handleInformationalResponses =>
          if (statusCode == StatusCodes.Continue)
            contextForCurrentResponse.get.oneHundredContinueTrigger.foreach(_.trySuccess(()))

          // http://tools.ietf.org/html/rfc7231#section-6.2 says:
          // "A client MUST be able to parse one or more 1xx responses received prior to a final response,
          // even if the client does not expect one."
          // so we simply drop this interim response and start parsing the next one
          startNewMessage(input, bodyStart)
        case _ =>
          emitResponseStart(emptyEntity(cth))
          setCompletionHandling(HttpMessageParser.CompletionOk)
          emit(MessageEnd)
          startNewMessage(input, bodyStart)
      }

    if (statusCode.allowsEntity) {
      contextForCurrentResponse.get.requestMethod match {
        case HttpMethods.HEAD => clh match {
            case Some(`Content-Length`(contentLength)) if contentLength > 0 =>
              emitResponseStart {
                StrictEntityCreator(HttpEntity.Default(contentType(cth), contentLength, Source.empty))
              }
              setCompletionHandling(HttpMessageParser.CompletionOk)
              emit(MessageEnd)
              startNewMessage(input, bodyStart)
            case _ => finishEmptyResponse()
          }
        case HttpMethods.CONNECT =>
          finishEmptyResponse()
        case _ =>
          if (!isChunked) {
            clh match {
              case Some(`Content-Length`(contentLength)) =>
                if (contentLength == 0) finishEmptyResponse()
                else if (contentLength <= input.size - bodyStart) {
                  val cl = contentLength.toInt
                  emitResponseStart(strictEntity(cth, input, bodyStart, cl))
                  setCompletionHandling(HttpMessageParser.CompletionOk)
                  emit(MessageEnd)
                  startNewMessage(input, bodyStart + cl)
                } else {
                  emitResponseStart(defaultEntity(cth, contentLength))
                  parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
                }
              case None =>
                emitResponseStart {
                  StreamedEntityCreator { entityParts =>
                    val data = entityParts.collect { case EntityPart(bytes) => bytes }
                    HttpEntity.CloseDelimited(contentType(cth), data)
                  }
                }
                setCompletionHandling(HttpMessageParser.CompletionOk)
                parseToCloseBody(input, bodyStart, totalBytesRead = 0)
            }
          } else {
            if (clh.isEmpty) {
              emitResponseStart(chunkedEntity(cth), headers)
              parseChunk(input, bodyStart, closeAfterResponseCompletion, totalBytesRead = 0L)
            } else failMessageStart("A chunked response must not contain a Content-Length header")
          }
      }
    } else finishEmptyResponse()
  }

  private def parseToCloseBody(input: ByteString, bodyStart: Int, totalBytesRead: Long): StateResult = {
    val newTotalBytes = totalBytesRead + math.max(0, input.length - bodyStart)
    if (input.length > bodyStart)
      emit(EntityPart(input.drop(bodyStart).compact))
    continue(parseToCloseBody(_, _, newTotalBytes))
  }
}

private[http] object HttpResponseParser {

  /**
   * @param requestMethod the request's HTTP method
   * @param oneHundredContinueTrigger if the request contains an `Expect: 100-continue` header this option contains
   *                                  a promise whose completion either triggers the sending of the (suspended)
   *                                  request entity or the closing of the connection (for error completion)
   */
  private[http] final case class ResponseContext(
      requestMethod: HttpMethod,
      oneHundredContinueTrigger: Option[Promise[Unit]])

  private[http] object OneHundredContinueError
      extends RuntimeException("Received error response for request with `Expect: 100-continue` header")
      with NoStackTrace
}
