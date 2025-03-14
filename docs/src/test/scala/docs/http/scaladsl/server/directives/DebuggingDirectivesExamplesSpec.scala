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

package docs.http.scaladsl.server.directives

import org.apache.pekko
import pekko.event.{ Logging, LoggingAdapter }
import pekko.event.Logging.LogLevel
import pekko.http.scaladsl.model.HttpRequest
import pekko.http.scaladsl.server.RouteResult
import pekko.http.scaladsl.server.RouteResult.{ Complete, Rejected }
import pekko.http.scaladsl.server.RoutingSpec
import pekko.http.scaladsl.server.directives.{ DebuggingDirectives, LogEntry, LoggingMagnet }
import docs.CompileOnlySpec

class DebuggingDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "logRequest-0" in {
    // #logRequest-0
    // different possibilities of using logRequest

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString
    DebuggingDirectives.logRequest("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString
    DebuggingDirectives.logRequest(("get-user", Logging.InfoLevel))

    // logs just the request method at debug level
    def requestMethod(req: HttpRequest): String = req.method.name
    DebuggingDirectives.logRequest(requestMethod _)

    // logs just the request method at info level
    def requestMethodAsInfo(req: HttpRequest): LogEntry = LogEntry(req.method.name, Logging.InfoLevel)
    DebuggingDirectives.logRequest(requestMethodAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethod(req: HttpRequest): Unit = println(req.method.name)
    val logRequestPrintln = DebuggingDirectives.logRequest(LoggingMagnet(_ => printRequestMethod))

    // tests:
    Get("/") ~> logRequestPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    // #logRequest-0
  }
  "logRequestResult" in {
    // #logRequestResult
    // different possibilities of using logRequestResult

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult("get-user")

    // marks with "get-user", log with info level, HttpRequest.toString, HttpResponse.toString
    DebuggingDirectives.logRequestResult(("get-user", Logging.InfoLevel))

    // logs just the request method and response status at info level
    def requestMethodAndResponseStatusAsInfo(req: HttpRequest): RouteResult => Option[LogEntry] = {
      case RouteResult.Complete(res) => Some(LogEntry(req.method.name + ": " + res.status, Logging.InfoLevel))
      case _                         => None // no log entries for rejections
    }
    DebuggingDirectives.logRequestResult(requestMethodAndResponseStatusAsInfo _)

    // This one will only log rejections
    val rejectionLogger: HttpRequest => RouteResult => Option[LogEntry] = req => {
      case Rejected(rejections) =>
        Some(LogEntry(s"Request: $req\nwas rejected with rejections:\n$rejections", Logging.DebugLevel))
      case _ => None
    }
    DebuggingDirectives.logRequestResult(rejectionLogger)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printRequestMethodAndResponseStatus(req: HttpRequest)(res: RouteResult): Unit =
      println(requestMethodAndResponseStatusAsInfo(req)(res).map(_.obj.toString).getOrElse(""))
    val logRequestResultPrintln =
      DebuggingDirectives.logRequestResult(LoggingMagnet(_ => printRequestMethodAndResponseStatus))

    // tests:
    Get("/") ~> logRequestResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    // #logRequestResult
  }
  "logResult" in {
    // #logResult
    // different possibilities of using logResponse

    // The first alternatives use an implicitly available LoggingContext for logging
    // marks with "get-user", log with debug level, HttpResponse.toString
    DebuggingDirectives.logResult("get-user")

    // marks with "get-user", log with info level, HttpResponse.toString
    DebuggingDirectives.logResult(("get-user", Logging.InfoLevel))

    // logs just the response status at debug level
    def responseStatus(res: RouteResult): String = res match {
      case RouteResult.Complete(x)          => x.status.toString
      case RouteResult.Rejected(rejections) => "Rejected: " + rejections.mkString(", ")
    }
    DebuggingDirectives.logResult(responseStatus _)

    // logs just the response status at info level
    def responseStatusAsInfo(res: RouteResult): LogEntry = LogEntry(responseStatus(res), Logging.InfoLevel)
    DebuggingDirectives.logResult(responseStatusAsInfo _)

    // This one doesn't use the implicit LoggingContext but uses `println` for logging
    def printResponseStatus(res: RouteResult): Unit = println(responseStatus(res))
    val logResultPrintln = DebuggingDirectives.logResult(LoggingMagnet(_ => printResponseStatus))

    // tests:
    Get("/") ~> logResultPrintln(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    // #logResult
  }
  "logRequestResultWithResponseTime" in {
    // #logRequestResultWithResponseTime

    def pekkoResponseTimeLoggingFunction(
        loggingAdapter: LoggingAdapter,
        requestTimestamp: Long,
        level: LogLevel = Logging.InfoLevel)(req: HttpRequest)(res: RouteResult): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val responseTimestamp: Long = System.nanoTime
          val elapsedTime: Long = (responseTimestamp - requestTimestamp) / 1000000
          val loggingString = s"""Logged Request:${req.method}:${req.uri}:${resp.status}:$elapsedTime"""
          LogEntry(loggingString, level)
        case Rejected(reason) =>
          LogEntry(s"Rejected Reason: ${reason.mkString(",")}", level)
      }
      entry.logTo(loggingAdapter)
    }
    def printResponseTime(log: LoggingAdapter) = {
      val requestTimestamp = System.nanoTime
      pekkoResponseTimeLoggingFunction(log, requestTimestamp) _
    }

    val logResponseTime = DebuggingDirectives.logRequestResult(LoggingMagnet(printResponseTime))

    Get("/") ~> logResponseTime(complete("logged")) ~> check {
      responseAs[String] shouldEqual "logged"
    }
    // #logRequestResultWithResponseTime
  }
}
