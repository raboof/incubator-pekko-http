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

package org.apache.pekko.http.scaladsl.unmarshalling

import org.apache.pekko
import pekko.event.Logging
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.util.FastFuture
import pekko.http.scaladsl.util.FastFuture._
import pekko.stream.Materializer

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.{ NoStackTrace, NonFatal }

trait Unmarshaller[-A, B] extends pekko.http.javadsl.unmarshalling.Unmarshaller[A, B] {

  implicit final def asScala: Unmarshaller[A, B] = this

  def apply(value: A)(implicit ec: ExecutionContext, materializer: Materializer): Future[B]

  def transform[C](f: ExecutionContext => Materializer => Future[B] => Future[C]): Unmarshaller[A, C] =
    Unmarshaller.withMaterializer { implicit ec => implicit mat => a => f(ec)(mat)(this(a)) }

  def map[C](f: B => C): Unmarshaller[A, C] =
    transform(implicit ec => _ => _.fast.map(f))

  def flatMap[C](f: ExecutionContext => Materializer => B => Future[C]): Unmarshaller[A, C] =
    transform(implicit ec => mat => _.fast.flatMap(f(ec)(mat)))

  def andThen[C](other: Unmarshaller[B, C]): Unmarshaller[A, C] =
    flatMap(ec => mat => data => other(data)(ec, mat))

  def recover[C >: B](pf: ExecutionContext => Materializer => PartialFunction[Throwable, C]): Unmarshaller[A, C] =
    transform(implicit ec => mat => _.fast.recover(pf(ec)(mat)))

  def withDefaultValue[BB >: B](defaultValue: BB): Unmarshaller[A, BB] =
    recover(_ => _ => { case Unmarshaller.NoContentException => defaultValue })
}

object Unmarshaller
    extends GenericUnmarshallers
    with PredefinedFromEntityUnmarshallers
    with PredefinedFromStringUnmarshallers {

  // format: OFF

  //#unmarshaller-creation
  /**
   * Creates an `Unmarshaller` from the given function.
   */
  def apply[A, B](f: ExecutionContext => A => Future[B]): Unmarshaller[A, B] =
    withMaterializer(ec => _ => f(ec))

  def withMaterializer[A, B](f: ExecutionContext => Materializer => A => Future[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] {
      def apply(a: A)(implicit ec: ExecutionContext, materializer: Materializer) =
        try f(ec)(materializer)(a)
        catch { case NonFatal(e) => FastFuture.failed(e) }
    }

  /**
   * Helper for creating a synchronous `Unmarshaller` from the given function.
   */
  def strict[A, B](f: A => B): Unmarshaller[A, B] = Unmarshaller(_ => a => FastFuture.successful(f(a)))

  /**
   * Helper for creating a "super-unmarshaller" from a sequence of "sub-unmarshallers", which are tried
   * in the given order. The first successful unmarshalling of a "sub-unmarshallers" is the one produced by the
   * "super-unmarshaller".
   */
  def firstOf[A, B](unmarshallers: Unmarshaller[A, B]*): Unmarshaller[A, B] = //...
  //#unmarshaller-creation
    Unmarshaller.withMaterializer { implicit ec => implicit mat => a =>
      def rec(ix: Int, supported: Set[ContentTypeRange], contentType: Option[ContentType]): Future[B] =
        if (ix < unmarshallers.size) {
          unmarshallers(ix)(a).fast.recoverWith {
            case e: UnsupportedContentTypeException =>
              rec(ix + 1, supported ++ e.supported, contentType.orElse(e.actualContentType))
          }
        } else FastFuture.failed(UnsupportedContentTypeException(supported, contentType))

      rec(0, Set.empty, None)
    }

  // format: ON

  implicit def identityUnmarshaller[T]: Unmarshaller[T, T] = Unmarshaller(_ => FastFuture.successful)

  // we don't define these methods directly on `Unmarshaller` due to variance constraints
  implicit class EnhancedUnmarshaller[A, B](val um: Unmarshaller[A, B]) extends AnyVal {
    def mapWithInput[C](f: (A, B) => C): Unmarshaller[A, C] =
      Unmarshaller.withMaterializer(implicit ec => implicit mat => a => um(a).fast.map(f(a, _)))

    def flatMapWithInput[C](f: (A, B) => Future[C]): Unmarshaller[A, C] =
      Unmarshaller.withMaterializer(implicit ec => implicit mat => a => um(a).fast.flatMap(f(a, _)))
  }

  implicit class EnhancedFromEntityUnmarshaller[A](val underlying: FromEntityUnmarshaller[A]) extends AnyVal {
    def mapWithCharset[B](f: (A, HttpCharset) => B): FromEntityUnmarshaller[B] =
      underlying.mapWithInput { (entity, data) => f(data, Unmarshaller.bestUnmarshallingCharsetFor(entity)) }

    /**
     * Modifies the underlying [[Unmarshaller]] to only accept Content-Types matching one of the given ranges.
     * Note that you can only restrict to a subset of the Content-Types accepted by the underlying unmarshaller,
     * i.e. the given ranges must be completely supported also by the underlying Unmarshaller!
     * If a violation of this rule is detected at runtime, i.e. if an entity is encountered whose Content-Type
     * is matched by one of the given ranges but rejected by the underlying unmarshaller
     * an IllegalStateException will be thrown!
     */
    def forContentTypes(ranges: ContentTypeRange*): FromEntityUnmarshaller[A] =
      Unmarshaller.withMaterializer { implicit ec => implicit mat => entity =>
        if (entity.contentType == ContentTypes.NoContentType || ranges.exists(_.matches(entity.contentType))) {
          underlying(entity).fast.recover[A](barkAtUnsupportedContentTypeException(ranges, entity.contentType))
        } else FastFuture.failed(UnsupportedContentTypeException(Some(entity.contentType), ranges: _*))
      }

    private def barkAtUnsupportedContentTypeException(
        ranges: Seq[ContentTypeRange],
        newContentType: ContentType): PartialFunction[Throwable, Nothing] = {
      case UnsupportedContentTypeException(supported) => throw new IllegalStateException(
          s"Illegal use of `unmarshaller.forContentTypes($ranges)`: Content-Type [$newContentType] is not supported by underlying marshaller!")
    }
  }

  /**
   * Returns the best charset for unmarshalling the given entity to a character-based representation.
   * Falls back to UTF-8 if no better alternative can be determined.
   */
  def bestUnmarshallingCharsetFor(entity: HttpEntity): HttpCharset =
    entity.contentType match {
      case x: ContentType.NonBinary => x.charset
      case _                        => HttpCharsets.`UTF-8`
    }

  /**
   * Signals that unmarshalling failed because the entity was unexpectedly empty.
   */
  case object NoContentException extends RuntimeException("Message entity must not be empty") with NoStackTrace

  /** Order of parameters (`right` first, `left` second) is intentional, since that's the order we evaluate them in. */
  final case class EitherUnmarshallingException(
      rightClass: Class[_], right: Throwable,
      leftClass: Class[_], left: Throwable)
      extends RuntimeException(
        s"Failed to unmarshal Either[${Logging.simpleName(leftClass)}, ${Logging.simpleName(
            rightClass)}] (attempted ${Logging.simpleName(rightClass)} first). " +
        s"Right failure: ${right.getMessage}, " +
        s"Left failure: ${left.getMessage}")

  /**
   * Signals that unmarshalling failed because the entity content-type did not match one of the supported ranges.
   * This error cannot be thrown by custom code, you need to use the `forContentTypes` modifier on a base
   * [[pekko.http.scaladsl.unmarshalling.Unmarshaller]] instead.
   */
  final class UnsupportedContentTypeException(
      val supported: Set[ContentTypeRange],
      val actualContentType: Option[ContentType])
      extends RuntimeException(supported.mkString(
        s"Unsupported Content-Type [$actualContentType], supported: ", ", ", "")) with Product with Serializable {

    @deprecated("for binary compatibility", since = "Akka HTTP 10.1.9")
    def this(supported: Set[ContentTypeRange]) = this(supported, None)

    @deprecated("for binary compatibility", since = "Akka HTTP 10.1.9")
    def copy(supported: Set[ContentTypeRange]): UnsupportedContentTypeException =
      new UnsupportedContentTypeException(supported, this.actualContentType)

    @deprecated("for binary compatibility", since = "Akka HTTP 10.1.9")
    def copy$default$1(supported: Set[ContentTypeRange]): UnsupportedContentTypeException =
      new UnsupportedContentTypeException(supported, this.actualContentType)

    @deprecated("for binary compatibility", since = "Akka HTTP 10.1.9")
    def copy(
        supported: Set[ContentTypeRange] = this.supported,
        contentType: Option[ContentType] = this.actualContentType): UnsupportedContentTypeException =
      new UnsupportedContentTypeException(supported, contentType)

    override def canEqual(that: Any): Boolean = that.isInstanceOf[UnsupportedContentTypeException]

    override def equals(that: Any): Boolean = that match {
      case that: UnsupportedContentTypeException =>
        that.canEqual(this) && that.supported == this.supported && that.actualContentType == this.actualContentType
      case _ => false
    }
    override def productArity: Int = 1
    override def productElement(n: Int): Any = supported
  }

  object UnsupportedContentTypeException {
    def apply(supported: Set[ContentTypeRange], actualContentType: Option[ContentType]) =
      new UnsupportedContentTypeException(supported, actualContentType)

    def apply(supported: ContentTypeRange*): UnsupportedContentTypeException =
      new UnsupportedContentTypeException(supported.toSet, None)

    def apply(supported: Set[ContentTypeRange]): UnsupportedContentTypeException =
      new UnsupportedContentTypeException(supported, None)

    def apply(contentType: Option[ContentType], supported: ContentTypeRange*): UnsupportedContentTypeException =
      UnsupportedContentTypeException(supported.toSet, contentType)

    def unapply(e: UnsupportedContentTypeException): Option[Set[ContentTypeRange]] =
      Some(e.supported)
  }

}
