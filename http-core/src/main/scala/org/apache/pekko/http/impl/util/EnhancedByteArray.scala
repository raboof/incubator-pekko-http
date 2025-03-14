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

package org.apache.pekko.http.impl.util

import org.apache.pekko.annotation.InternalApi

import scala.annotation.tailrec

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedByteArray(val underlying: Array[Byte]) extends AnyVal {

  /**
   * Tests two byte arrays for value equality in a way that defends against timing attacks.
   * Simple equality testing will stop at the end of a matching prefix thereby leaking information
   * about the length of the matching prefix which can be exploited for per-byte progressive brute-forcing.
   *
   * @note This function leaks information about the length of each byte array as well as
   *       whether the two byte arrays have the same length.
   * @see [[http://codahale.com/a-lesson-in-timing-attacks/]]
   * @see [[http://rdist.root.org/2009/05/28/timing-attack-in-google-keyczar-library/]]
   * @see [[http://emerose.com/timing-attacks-explained]]
   */
  def secure_==(other: Array[Byte]): Boolean = {
    @tailrec def xor(ix: Int = 0, result: Int = 0): Int =
      if (ix < underlying.length) xor(ix + 1, result | (underlying(ix) ^ other(ix))) else result

    other.length == underlying.length && xor() == 0
  }
}
