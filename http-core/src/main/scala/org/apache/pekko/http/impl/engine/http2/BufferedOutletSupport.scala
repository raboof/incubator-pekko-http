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

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.stream.Outlet
import pekko.stream.stage.GraphStageLogic
import pekko.stream.stage.OutHandler

/**
 * INTERNAL API
 */
@InternalApi
private[http2] trait GenericOutlet[T] {
  def setHandler(handler: OutHandler): Unit
  def push(elem: T): Unit
  def complete(): Unit
  def fail(cause: Throwable): Unit
  def canBePushed: Boolean
}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] class BufferedOutlet[T](outlet: GenericOutlet[T]) extends OutHandler {
  val buffer: java.util.ArrayDeque[T] = new java.util.ArrayDeque[T]
  var completed = false

  /**
   * override to hook into actually pushing, e.g. to keep track how much
   * has been pushed already (in contract, to being still cached)
   */
  protected def doPush(elem: T): Unit = outlet.push(elem)

  outlet.setHandler(this)

  def onPull(): Unit = tryFlush()
  def push(elem: T): Unit =
    if (outlet.canBePushed && buffer.isEmpty) doPush(elem)
    else buffer.addLast(elem)

  def complete(): Unit = {
    require(!completed, "Can only complete once.")
    completed = true
    if (buffer.isEmpty) outlet.complete()
  }

  def fail(cause: Throwable): Unit = {
    buffer.clear()
    outlet.fail(cause)
  }

  def tryFlush(): Unit = {
    if (outlet.canBePushed && !buffer.isEmpty)
      doPush(buffer.pop())

    if (buffer.isEmpty && completed) outlet.complete()
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] class BufferedOutletExtended[T](outlet: GenericOutlet[T]) extends OutHandler {
  case class ElementAndTrigger(element: T, trigger: () => Unit)
  final val buffer: java.util.ArrayDeque[ElementAndTrigger] = new java.util.ArrayDeque[ElementAndTrigger]

  /**
   * override to hook into actually pushing, e.g. to keep track how much
   * has been pushed already (in contract, to being still cached)
   */
  protected def doPush(elem: ElementAndTrigger): Unit = {
    outlet.push(elem.element)
    elem.trigger()
  }

  override def onPull(): Unit =
    if (!buffer.isEmpty) doPush(buffer.pop())

  outlet.setHandler(this)

  final def push(element: T): Unit = pushWithTrigger(element, () => ())
  final def pushWithTrigger(elem: T, trigger: () => Unit): Unit =
    if (outlet.canBePushed && buffer.isEmpty) doPush(ElementAndTrigger(elem, trigger))
    else buffer.addLast(ElementAndTrigger(elem, trigger))

  def tryFlush(): Unit =
    if (outlet.canBePushed && !buffer.isEmpty)
      doPush(buffer.pop())
}

/**
 * INTERNAL API
 */
@InternalApi
private[http2] trait GenericOutletSupport { logic: GraphStageLogic =>
  def fromSubSourceOutlet[T](subSourceOutlet: SubSourceOutlet[T]): GenericOutlet[T] =
    new GenericOutlet[T] {
      def setHandler(handler: OutHandler): Unit = subSourceOutlet.setHandler(handler)
      def push(elem: T): Unit = subSourceOutlet.push(elem)
      def complete(): Unit = subSourceOutlet.complete()
      def fail(cause: Throwable): Unit = subSourceOutlet.fail(cause)
      def canBePushed: Boolean = subSourceOutlet.isAvailable
    }
  def fromOutlet[T](outlet: Outlet[T]): GenericOutlet[T] =
    new GenericOutlet[T] {
      def setHandler(handler: OutHandler): Unit = logic.setHandler(outlet, handler)
      def push(elem: T): Unit = logic.emit(outlet, elem)
      def complete(): Unit = logic.complete(outlet)
      def fail(cause: Throwable): Unit = logic.fail(outlet, cause)
      def canBePushed: Boolean = logic.isAvailable(outlet)
    }
}
