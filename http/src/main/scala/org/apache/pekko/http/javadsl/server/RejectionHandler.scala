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

package org.apache.pekko.http.javadsl.server

import org.apache.pekko
import pekko.http.scaladsl.server
import pekko.http.impl.util.JavaMapping
import java.util.function

import pekko.http.javadsl.model.HttpResponse

import scala.reflect.ClassTag
import scala.collection.JavaConverters._

object RejectionHandler {

  /**
   * Creates a new [[RejectionHandler]] builder.
   */
  def newBuilder = new RejectionHandlerBuilder(server.RejectionHandler.newBuilder())

  def defaultHandler = new RejectionHandler(server.RejectionHandler.default)
}

final class RejectionHandler(val asScala: server.RejectionHandler) {

  /**
   * Creates a new [[RejectionHandler]] which uses the given one as fallback for this one.
   */
  def withFallback(fallback: RejectionHandler) = new RejectionHandler(asScala.withFallback(fallback.asScala))

  /**
   * "Seals" this handler by attaching a default handler as fallback if necessary.
   */
  def seal = new RejectionHandler(asScala.seal)

  /** Map any HTTP response which was returned by this RejectionHandler to a different one before rendering it. */
  def mapRejectionResponse(map: function.UnaryOperator[HttpResponse]): RejectionHandler =
    new RejectionHandler(asScala.mapRejectionResponse(resp => {
      JavaMapping.toScala(map.apply(resp))
    }))
}

class RejectionHandlerBuilder(asScala: server.RejectionHandler.Builder) {
  def build = new RejectionHandler(asScala.result())

  /**
   * Handles a single [[Rejection]] with the given function.
   */
  def handle[T <: Rejection](t: Class[T], handler: function.Function[T, Route]): RejectionHandlerBuilder = {
    asScala.handle { case r if t.isInstance(r) => handler.apply(t.cast(r)).delegate }
    this
  }

  /**
   * Handles several Rejections of the same type at the same time.
   * The list passed to the given function is guaranteed to be non-empty.
   */
  def handleAll[T <: Rejection](
      t: Class[T], handler: function.Function[java.util.List[T], Route]): RejectionHandlerBuilder = {
    asScala.handleAll { rejections: collection.immutable.Seq[T] => handler.apply(rejections.asJava).delegate }(
      ClassTag(t))
    this
  }

  /**
   * Handles the special "not found" case using the given [[Route]].
   */
  def handleNotFound(route: Route): RejectionHandlerBuilder = {
    asScala.handleNotFound(route.delegate)
    this
  }

  /**
   * Convenience method for handling rejections created by created by the onCompleteWithBreaker directive.
   * Signals that the request was rejected because the supplied circuit breaker is open and requests are failing fast.
   *
   * Use to customise the error response being written instead of the default [[pekko.http.javadsl.model.StatusCodes.SERVICE_UNAVAILABLE]] response.
   */
  def handleCircuitBreakerOpenRejection(
      handler: function.Function[CircuitBreakerOpenRejection, Route]): RejectionHandlerBuilder = {
    asScala.handleCircuitBreakerOpenRejection(t => handler.apply(t).delegate)
    this
  }
}
