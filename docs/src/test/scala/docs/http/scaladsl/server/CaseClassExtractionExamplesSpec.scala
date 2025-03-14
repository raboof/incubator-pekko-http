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

import org.scalatest.Inside
import org.apache.pekko.http.scaladsl.server._

class CaseClassExtractionExamplesSpec extends RoutingSpec with Inside {

  "example-1" in {
    // #example-1
    case class Color(red: Int, green: Int, blue: Int)

    val route =
      path("color") {
        parameters("red".as[Int], "green".as[Int], "blue".as[Int]) { (red, green, blue) =>
          val color = Color(red, green, blue)
          // ... route working with the `color` instance
          doSomethingWith(color) // #hide
        }
      }
    Get("/color?red=1&green=2&blue=3") ~> route ~> check { responseAs[String] shouldEqual "Color(1,2,3)" } // #hide
    // #example-1
  }

  "example-2" in {
    object Color {}
    // #example-2
    case class Color(red: Int, green: Int, blue: Int)

    val route =
      path("color") {
        parameters("red".as[Int], "green".as[Int], "blue".as[Int]).as(Color.apply _) { color =>
          // ... route working with the `color` instance
          doSomethingWith(color) // #hide
        }
      }
    Get("/color?red=1&green=2&blue=3") ~> route ~> check { responseAs[String] shouldEqual "Color(1,2,3)" } // #hide
    // #example-2
  }

  "example-3" in {
    object Color {}
    // #example-3
    case class Color(name: String, red: Int, green: Int, blue: Int)

    val route =
      (path("color" / Segment) & parameters("r".as[Int], "g".as[Int], "b".as[Int]))
        .as(Color.apply _) { color =>
          // ... route working with the `color` instance
          doSomethingWith(color) // #hide
        }
    Get("/color/abc?r=1&g=2&b=3") ~> route ~> check { responseAs[String] shouldEqual "Color(abc,1,2,3)" } // #hide
    // #example-3
  }

  // #example-4
  case class Color(name: String, red: Int, green: Int, blue: Int) {
    require(!name.isEmpty, "color name must not be empty")
    require(0 <= red && red <= 255, "red color component must be between 0 and 255")
    require(0 <= green && green <= 255, "green color component must be between 0 and 255")
    require(0 <= blue && blue <= 255, "blue color component must be between 0 and 255")
  }
  // #example-4

  "example 4 test" in {
    val route =
      (path("color" / Segment) &
        parameters("r".as[Int], "g".as[Int], "b".as[Int])).as(Color) { color =>
        // ... route working with the `color` instance
        doSomethingWith(color) // #hide
      }
    Get("/color/abc?r=1&g=2&b=3") ~> route ~> check {
      responseAs[String] shouldEqual "Color(abc,1,2,3)"
    }
    Get("/color/abc?r=1&g=2&b=345") ~> route ~> check {
      inside(rejection) {
        case ValidationRejection("requirement failed: blue color component must be between 0 and 255", _) =>
      }
    }
  }

  def doSomethingWith(x: Any) = complete(x.toString)
}
