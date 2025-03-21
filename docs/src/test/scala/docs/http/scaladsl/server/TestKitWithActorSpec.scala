/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

//#testkit-actor-integration
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.{ ActorRef, Scheduler }
import pekko.actor.typed.scaladsl.AskPattern._
import pekko.http.scaladsl.server.Directives._
import pekko.http.scaladsl.testkit.ScalatestRouteTest
import pekko.util.Timeout

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object RouteUnderTest {
  case class Ping(replyTo: ActorRef[String])

  // Your route under test, scheduler is only needed as ask is used
  def route(someActor: ActorRef[Ping])(implicit scheduler: Scheduler, timeout: Timeout) = get {
    path("ping") {
      complete(someActor ? Ping)
    }
  }
}

class TestKitWithActorSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  import RouteUnderTest._

  // This test does not use the classic APIs,
  // so it needs to adapt the system:
  import pekko.actor.typed.scaladsl.adapter._
  implicit val typedSystem = system.toTyped
  implicit val timeout = Timeout(500.milliseconds)
  implicit val scheduler = system.scheduler

  "The service" should {
    "return a 'PONG!' response for GET requests to /ping" in {
      val probe = TestProbe[Ping]()
      val test = Get("/ping") ~> RouteUnderTest.route(probe.ref)
      val ping = probe.expectMessageType[Ping]
      ping.replyTo ! "PONG!"
      test ~> check {
        responseAs[String] shouldEqual "PONG!"
      }
    }
  }
}
//#testkit-actor-integration
