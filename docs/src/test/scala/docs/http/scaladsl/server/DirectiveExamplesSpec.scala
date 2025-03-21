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

package docs.http.scaladsl.server

import org.apache.pekko.http.scaladsl.server._
import docs.CompileOnlySpec

class DirectiveExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "example-1" in {
    // #example-1
    val route: Route =
      path("order" / IntNumber) { id =>
        concat(
          get {
            complete {
              "Received GET request for order " + id
            }
          },
          put {
            complete {
              "Received PUT request for order " + id
            }
          })
      }
    verify(route) // #hide
    // #example-1
  }

  "example-2" in {
    // #getOrPut
    def innerRoute(id: Int): Route =
      concat(
        get {
          complete {
            "Received GET request for order " + id
          }
        },
        put {
          complete {
            "Received PUT request for order " + id
          }
        })

    val route: Route = path("order" / IntNumber) { id => innerRoute(id) }
    verify(route) // #hide
    // #getOrPut
  }

  "example-3" in {
    // #getOrPutUsingPipe
    val route =
      path("order" / IntNumber) { id =>
        (get | put) { ctx =>
          ctx.complete(s"Received ${ctx.request.method.name} request for order $id")
        }
      }
    verify(route) // #hide
    // #getOrPutUsingPipe
  }

  "example-4" in {
    // #getOrPutUsingPipeAndExtractMethod
    val route =
      path("order" / IntNumber) { id =>
        (get | put) {
          extractMethod { m =>
            complete(s"Received ${m.name} request for order $id")
          }
        }
      }
    verify(route) // #hide
    // #getOrPutUsingPipeAndExtractMethod
  }

  "example-5" in {
    // #example-5
    val getOrPut = get | put
    val route =
      path("order" / IntNumber) { id =>
        getOrPut {
          extractMethod { m =>
            complete(s"Received ${m.name} request for order $id")
          }
        }
      }
    verify(route) // #hide
    // #example-5
  }

  "example-6" in {
    // #example-6
    val getOrPut = get | put
    val route =
      (path("order" / IntNumber) & getOrPut & extractMethod) { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // #hide
    // #example-6
  }

  "example-7" in {
    // #example-7
    val orderGetOrPutWithMethod =
      path("order" / IntNumber) & (get | put) & extractMethod
    val route =
      orderGetOrPutWithMethod { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // #hide
    // #example-7
  }

  "example-8" in {
    // #example-8
    def innerRoute(id: Int): Route =
      get {
        complete {
          "Received GET request for order " + id
        }
      } ~
      put {
        complete {
          "Received PUT request for order " + id
        }
      }

    val route: Route = path("order" / IntNumber) { id => innerRoute(id) }
    verify(route) // #hide
    // #example-8
  }

  def verify(route: Route) = {
    Get("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received GET request for order 42" }
    Put("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received PUT request for order 42" }
    Get("/")         ~> route ~> check { handled shouldEqual false }
  }
}
