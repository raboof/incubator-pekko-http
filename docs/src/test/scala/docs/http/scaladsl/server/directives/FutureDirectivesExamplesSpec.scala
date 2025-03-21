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

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import org.apache.pekko
import pekko.http.scaladsl.server.{ CircuitBreakerOpenRejection, ExceptionHandler, Route }
import pekko.util.Timeout
import pekko.http.scaladsl.model._
import StatusCodes._
import pekko.http.scaladsl.server.RoutingSpec
import pekko.pattern.CircuitBreaker
import docs.CompileOnlySpec

class FutureDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  object TestException extends Throwable

  implicit val myExceptionHandler =
    ExceptionHandler {
      case TestException => complete(InternalServerError -> "Unsuccessful future!")
    }

  implicit val responseTimeout = Timeout(2, TimeUnit.SECONDS)

  "onComplete" in {
    // #onComplete
    def divide(a: Int, b: Int): Future[Int] = Future {
      a / b
    }

    val route =
      path("divide" / IntNumber / IntNumber) { (a, b) =>
        onComplete(divide(a, b)) {
          case Success(value) => complete(s"The result was $value")
          case Failure(ex)    => complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
        }
      }

    // tests:
    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }

    Get("/divide/10/0") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "An error occurred: / by zero"
    }
    // #onComplete
  }

  "onCompleteWithBreaker" in {
    // The test has a race condition because CircuitBreakers do not guarantee certain happens-before relationships
    // between triggering and reporting errors for ongoing calls. This test fails a lot so disabling for now.
    pending

    // #onCompleteWithBreaker
    def divide(a: Int, b: Int): Future[Int] = Future {
      a / b
    }

    val resetTimeout = 1.second
    val breaker = new CircuitBreaker(
      system.scheduler,
      maxFailures = 1,
      callTimeout = 5.seconds,
      resetTimeout)

    val route =
      path("divide" / IntNumber / IntNumber) { (a, b) =>
        onCompleteWithBreaker(breaker)(divide(a, b)) {
          case Success(value) => complete(s"The result was $value")
          case Failure(ex)    => complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
        }
      }

    // tests:
    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }

    Get("/divide/10/0") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "An error occurred: / by zero"
    } // opens the circuit breaker

    Get("/divide/10/2") ~> route ~> check {
      rejection shouldBe a[CircuitBreakerOpenRejection]
    }

    Thread.sleep(resetTimeout.toMillis + 200)

    // #onCompleteWithBreaker
    // retry to make test more stable, since breaker reset is timer based, hidden from docs
    // format: OFF
    awaitAssert({
    //#onCompleteWithBreaker
    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }
    //#onCompleteWithBreaker
    }, 500.millis)
    // format: ON
  }

  "onSuccess" in {
    // #onSuccess
    val route =
      concat(
        path("success") {
          onSuccess(Future { "Ok" }) { extraction =>
            complete(extraction)
          }
        },
        path("failure") {
          onSuccess(Future.failed[String](TestException)) { extraction =>
            complete(extraction)
          }
        })

    // tests:
    Get("/success") ~> route ~> check {
      responseAs[String] shouldEqual "Ok"
    }

    Get("/failure") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "Unsuccessful future!"
    }
    // #onSuccess
  }

  "completeOrRecoverWith" in {
    // #completeOrRecoverWith
    val route =
      concat(
        path("success") {
          completeOrRecoverWith(Future { "Ok" }) { extraction =>
            failWith(extraction) // not executed.
          }
        },
        path("failure") {
          completeOrRecoverWith(Future.failed[String](TestException)) { extraction =>
            failWith(extraction)
          }
        })

    // tests:
    Get("/success") ~> route ~> check {
      responseAs[String] shouldEqual "Ok"
    }

    Get("/failure") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "Unsuccessful future!"
    }
    // #completeOrRecoverWith
  }
}
