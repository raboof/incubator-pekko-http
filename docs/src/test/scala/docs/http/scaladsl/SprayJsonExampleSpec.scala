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

package docs.http.scaladsl

import scala.annotation.nowarn
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@nowarn("msg=will not be a runnable program")
class SprayJsonExampleSpec extends AnyWordSpec with Matchers {

  def compileOnlySpec(body: => Unit) = ()

  "spray-json example" in compileOnlySpec {
    // #minimal-spray-json-example
    import org.apache.pekko
    import pekko.http.scaladsl.server.Directives
    import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    import spray.json._

    // domain model
    final case class Item(name: String, id: Long)
    final case class Order(items: List[Item])

    // collect your json format instances into a support trait:
    trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
      implicit val itemFormat = jsonFormat2(Item)
      implicit val orderFormat = jsonFormat1(Order) // contains List[Item]
    }

    // use it wherever json (un)marshalling is needed
    class MyJsonService extends Directives with JsonSupport {

      val route =
        concat(
          get {
            pathSingleSlash {
              complete(Item("thing", 42)) // will render as JSON
            }
          },
          post {
            entity(as[Order]) { order => // will unmarshal JSON to Order
              val itemsCount = order.items.size
              val itemNames = order.items.map(_.name).mkString(", ")
              complete(s"Ordered $itemsCount items: $itemNames")
            }
          })
    }
    // #minimal-spray-json-example
  }
}
