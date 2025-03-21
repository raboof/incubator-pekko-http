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

package org.apache.pekko.http.scaladsl.server

import org.apache.pekko
import pekko.http.scaladsl.model
import model.HttpMethods._
import model.StatusCodes
import pekko.testkit.EventFilter

object BasicRouteSpecs {
  private[http] def defaultExnHandler500Error(message: String) = {
    ExceptionHandler.ErrorMessageTemplate
      .replaceFirst("""\{\}""", message)
      .replaceFirst("""\{\}""", StatusCodes.InternalServerError.toString)
  }
}

class BasicRouteSpecs extends RoutingSpec {

  "routes created by the concatenation operator '~'" should {
    "yield the first sub route if it succeeded" in {
      Get() ~> {
        get { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] shouldEqual "first" }
    }
    "yield the second sub route if the first did not succeed" in {
      Get() ~> {
        post { complete("first") } ~ get { complete("second") }
      } ~> check { responseAs[String] shouldEqual "second" }
    }
    "collect rejections from both sub routes" in {
      Delete() ~> {
        get { completeOk } ~ put { completeOk }
      } ~> check { rejections shouldEqual Seq(MethodRejection(GET), MethodRejection(PUT)) }
    }
    "clear rejections that have already been 'overcome' by previous directives" in {
      Put() ~> {
        put { parameter("yeah") { echoComplete } } ~
        get { completeOk }
      } ~> check { rejection shouldEqual MissingQueryParamRejection("yeah") }
    }
  }

  "routes created by the 'sequence' directive" should {
    "reject with zero arguments, since no routes were matched" in {
      Get() ~> {
        concat()
      } ~> check { rejections shouldEqual Seq() }
    }
    "yield the first sub route if it succeeded" in {
      Get() ~> {
        concat(
          get { complete("first") },
          get { complete("second") })
      } ~> check { responseAs[String] shouldEqual "first" }
    }
    "yield the second sub route if the first did not succeed" in {
      Get() ~> {
        concat(
          post { complete("first") },
          get { complete("second") })
      } ~> check { responseAs[String] shouldEqual "second" }
    }
    "collect rejections from both sub routes" in {
      Delete() ~> {
        concat(
          get { completeOk },
          put { completeOk })
      } ~> check { rejections shouldEqual Seq(MethodRejection(GET), MethodRejection(PUT)) }
    }
    "clear rejections that have already been 'overcome' by previous directives" in {
      Put() ~> {
        concat(
          put { parameter("yeah") { echoComplete } },
          get { completeOk })
      } ~> check { rejection shouldEqual MissingQueryParamRejection("yeah") }
    }
  }

  "Route conjunction" should {
    val stringDirective = provide("The cat")
    val intDirective = provide(42)
    val doubleDirective = provide(23.0)

    val dirStringInt = stringDirective & intDirective
    val dirStringIntDouble = dirStringInt & doubleDirective
    val dirDoubleStringInt = doubleDirective & dirStringInt
    val dirStringIntStringInt = dirStringInt & dirStringInt

    "work for two elements" in {
      Get("/abc") ~> {
        dirStringInt { (str, i) =>
          complete(s"$str ${i + 1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43" }
    }
    "work for 2 + 1" in {
      Get("/abc") ~> {
        dirStringIntDouble { (str, i, d) =>
          complete(s"$str ${i + 1} ${d + 0.1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43 23.1" }
    }
    "work for 1 + 2" in {
      Get("/abc") ~> {
        dirDoubleStringInt { (d, str, i) =>
          complete(s"$str ${i + 1} ${d + 0.1}")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 43 23.1" }
    }
    "work for 2 + 2" in {
      Get("/abc") ~> {
        dirStringIntStringInt { (str, i, str2, i2) =>
          complete(s"$str ${i + i2} $str2")
        }
      } ~> check { responseAs[String] shouldEqual "The cat 84 The cat" }
    }
  }
  "Route disjunction" should {
    "work in the happy case" in {
      val route = Route.seal((path("abc") | path("def")) {
        completeOk
      })

      Get("/abc") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
      Get("/def") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
      Get("/ghi") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }
    "don't apply alternative if inner route rejects" in {
      object MyRejection extends Rejection
      val route = (path("abc") | post) {
        reject(MyRejection)
      }
      Get("/abc") ~> route ~> check {
        rejection shouldEqual MyRejection
      }
    }
  }
  "Case class extraction with Directive.as" should {
    "extract one argument" in {
      case class MyNumber(i: Int)

      val abcPath = path("abc" / IntNumber).as(MyNumber)(echoComplete)

      Get("/abc/5") ~> abcPath ~> check {
        responseAs[String] shouldEqual "MyNumber(5)"
      }
    }
    "extract two arguments" in {
      case class Person(name: String, age: Int)

      val personPath = path("person" / Segment / IntNumber).as(Person)(echoComplete)

      Get("/person/john/38") ~> personPath ~> check {
        responseAs[String] shouldEqual "Person(john,38)"
      }
    }
    "reject if case class requirements fail" in {
      case class MyValidNumber(i: Int) {
        require(i > 10)
      }

      val abcPath = path("abc" / IntNumber).as(MyValidNumber)(echoComplete)

      Get("/abc/5") ~> abcPath ~> check {
        rejection shouldBe a[ValidationRejection]
      }
    }
  }
  "Dynamic execution of inner routes of Directive0" should {
    "re-execute inner routes every time" in {
      var a = ""
      val dynamicRoute = get { a += "x"; complete(a) }
      def expect(route: Route, s: String) = Get() ~> route ~> check { responseAs[String] shouldEqual s }

      expect(dynamicRoute, "x")
      expect(dynamicRoute, "xx")
      expect(dynamicRoute, "xxx")
      expect(dynamicRoute, "xxxx")
    }
  }

  case object MyException extends RuntimeException("Boom")
  "Route sealing" should {
    "catch route execution exceptions" in EventFilter[MyException.type](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("Boom")).intercept {
      Get("/abc") ~> Route.seal {
        get { ctx =>
          throw MyException
        }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
    "catch route building exceptions" in EventFilter[MyException.type](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("Boom")).intercept {
      Get("/abc") ~> Route.seal {
        get {
          throw MyException
        }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
    "convert all rejections to responses" in EventFilter[RuntimeException](
      occurrences = 1,
      start = "Error during processing of request: 'Unhandled rejection:").intercept {
      object MyRejection extends Rejection
      Get("/abc") ~> Route.seal {
        get {
          reject(MyRejection)
        }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
    "always prioritize MethodRejections over AuthorizationFailedRejections" in {
      Get("/abc") ~> Route.seal {
        post { completeOk } ~
        authorize(false) { completeOk }
      } ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
        responseAs[String] shouldEqual "HTTP method not allowed, supported methods: POST"
      }

      Get("/abc") ~> Route.seal {
        authorize(false) { completeOk } ~
        post { completeOk }
      } ~> check { status shouldEqual StatusCodes.MethodNotAllowed }
    }
  }
}
