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

package org.apache.pekko.http.javadsl.model

import org.apache.pekko
import pekko.http.javadsl.model.headers.Cookie
import pekko.http.scaladsl.model
import pekko.http.scaladsl.model.headers.BasicHttpCredentials

import scala.collection.immutable
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class JavaApiTestCaseSpecs extends AnyFreeSpec with Matchers {
  "JavaApiTestCases should work as intended" - {
    "buildRequest" in {
      JavaApiTestCases.buildRequest() must be(
        model.HttpRequest(
          model.HttpMethods.POST,
          uri = "/send"))
    }
    "handleRequest" - {
      "wrong method" in {
        JavaApiTestCases.handleRequest(model.HttpRequest(model.HttpMethods.HEAD)) must be(
          model.HttpResponse(model.StatusCodes.MethodNotAllowed, entity = "Unsupported method"))
      }
      "missing path" in {
        JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/blubber")) must be(
          model.HttpResponse(model.StatusCodes.NotFound, entity = "Not found"))
      }
      "happy path" - {
        "with name parameter" in {
          JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/hello?name=Peter")) must be(
            model.HttpResponse(entity = "Hello Peter!"))
        }
        "without name parameter" in {
          JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/hello")) must be(
            model.HttpResponse(entity = "Hello Mister X!"))
        }
      }
    }
    "addAuthentication" in {
      JavaApiTestCases.addAuthentication(model.HttpRequest()) must be(
        model.HttpRequest(headers = immutable.Seq(model.headers.Authorization(BasicHttpCredentials("username",
          "password")))))
    }
    "removeCookies" in {
      val testRequest = model.HttpRequest(headers = immutable.Seq(Cookie.create("test", "blub")))
      JavaApiTestCases.removeCookies(testRequest) must be(
        model.HttpRequest())
    }
    "createUriForOrder" in {
      JavaApiTestCases.createUriForOrder("123", "149", "42") must be(
        Uri.create("/order?orderId=123&price=149&amount=42"))
    }
    "addSessionId" in {
      val orderId = Query.create("orderId=123")
      Uri.create("/order").query(JavaApiTestCases.addSessionId(orderId)) must be(
        Uri.create("/order?orderId=123&session=abcdefghijkl"))
    }
  }
}
