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

package org.apache.pekko.http.impl.engine.http2

import org.scalactic.source
import org.scalatest.wordspec.AnyWordSpecLike

/** Adds `"test" inPendingUntilFixed {...}` which is equivalent to `"test" in pendingUntilFixed({...})` */
trait WithInPendingUntilFixed extends AnyWordSpecLike {
  implicit class InPendingUntilFixed(val str: String) {
    def inPendingUntilFixed(f: => Any /* Assertion */ )(implicit pos: source.Position): Unit =
      str.in(pendingUntilFixed(f))(pos)
  }
}
