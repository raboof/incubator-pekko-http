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

package org.apache.pekko.http.scaladsl.server
package directives

import scala.concurrent.Promise
import scala.util.{ Failure, Success }

import org.apache.pekko
import pekko.http.impl.util._
import pekko.http.scaladsl.model.ExceptionWithErrorInfo
import pekko.http.scaladsl.marshalling.ToResponseMarshaller
import pekko.http.scaladsl.unmarshalling.{ FromRequestUnmarshaller, Unmarshaller }
import pekko.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException

/**
 * @groupname marshalling Marshalling directives
 * @groupprio marshalling 120
 */
trait MarshallingDirectives {
  import BasicDirectives._
  import FutureDirectives._
  import RouteDirectives._

  /**
   * Unmarshalls the requests entity to the given type passes it to its inner Route.
   * If there is a problem with unmarshalling the request is rejected with the [[Rejection]]
   * produced by the unmarshaller.
   *
   * @group marshalling
   */
  def entity[T](um: FromRequestUnmarshaller[T]): Directive1[T] =
    extractRequestContext.flatMap[Tuple1[T]] { ctx =>
      import ctx.executionContext
      import ctx.materializer
      onComplete(um(ctx.request)).flatMap {
        case Success(value)                           => provide(value)
        case Failure(RejectionError(r))               => reject(r)
        case Failure(Unmarshaller.NoContentException) => reject(RequestEntityExpectedRejection)
        case Failure(x: UnsupportedContentTypeException) =>
          reject(UnsupportedRequestContentTypeRejection(x.supported, x.actualContentType))
        case Failure(x: IllegalArgumentException) => reject(ValidationRejection(x.getMessage.nullAsEmpty, Some(x)))
        case Failure(x: ExceptionWithErrorInfo) =>
          reject(MalformedRequestContentRejection(x.info.format(ctx.settings.verboseErrorMessages), x))
        case Failure(x) => reject(MalformedRequestContentRejection(x.getMessage.nullAsEmpty, x))
      }
    } & cancelRejections(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection])

  /**
   * Returns the in-scope [[FromRequestUnmarshaller]] for the given type.
   *
   * @group marshalling
   */
  def as[T](implicit um: FromRequestUnmarshaller[T]) = um

  /**
   * Uses the marshaller for the given type to produce a completion function that is passed to its inner function.
   * You can use it do decouple marshaller resolution from request completion.
   *
   * @group marshalling
   */
  def completeWith[T](marshaller: ToResponseMarshaller[T])(inner: (T => Unit) => Unit): Route =
    extractRequestContext { ctx =>
      implicit val m = marshaller
      complete {
        val promise = Promise[T]()
        inner(promise.success)
        promise.future
      }
    }

  /**
   * Returns the in-scope Marshaller for the given type.
   *
   * @group marshalling
   */
  def instanceOf[T](implicit m: ToResponseMarshaller[T]): ToResponseMarshaller[T] = m

  /**
   * Completes the request using the given function. The input to the function is produced with the in-scope
   * entity unmarshaller and the result value of the function is marshalled with the in-scope marshaller.
   *
   * @group marshalling
   */
  def handleWith[A, B](f: A => B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    entity(um) { a => complete(f(a)) }
}

object MarshallingDirectives extends MarshallingDirectives
