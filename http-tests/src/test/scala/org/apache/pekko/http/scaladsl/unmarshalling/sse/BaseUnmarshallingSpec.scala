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

package org.apache.pekko.http
package scaladsl
package unmarshalling
package sse

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.stream.{ ActorMaterializer, Materializer }
import org.scalatest.{ BeforeAndAfterAll, Suite }
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

trait BaseUnmarshallingSpec extends BeforeAndAfterAll { this: Suite =>

  protected implicit val system: ActorSystem =
    ActorSystem()

  protected implicit val mat: Materializer =
    ActorMaterializer()

  override protected def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}
