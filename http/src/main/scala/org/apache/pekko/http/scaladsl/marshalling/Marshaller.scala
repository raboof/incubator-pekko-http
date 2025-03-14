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

package org.apache.pekko.http.scaladsl.marshalling

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import org.apache.pekko
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.util.FastFuture
import pekko.http.scaladsl.util.FastFuture._

// TODO make it extend JavaDSL
sealed abstract class Marshaller[-A, +B] {

  def apply(value: A)(implicit ec: ExecutionContext): Future[List[Marshalling[B]]]

  def map[C](f: B => C): Marshaller[A, C] =
    Marshaller(implicit ec => value => this(value).fast.map(_.map(_.map(f))))

  /**
   * Reuses this Marshaller's logic to produce a new Marshaller from another type `C` which overrides
   * the [[pekko.http.scaladsl.model.MediaType]] of the marshalling result with the given one.
   * Note that not all wrappings are legal. f the underlying [[pekko.http.scaladsl.model.MediaType]] has constraints with regard to the
   * charsets it allows the new [[pekko.http.scaladsl.model.MediaType]] must be compatible, since pekko-http will never recode entities.
   * If the wrapping is illegal the [[scala.concurrent.Future]] produced by the resulting marshaller will contain a [[RuntimeException]].
   */
  def wrap[C, D >: B](newMediaType: MediaType)(f: C => A)(implicit mto: ContentTypeOverrider[D]): Marshaller[C, D] =
    wrapWithEC[C, D](newMediaType)(_ => f)

  /**
   * Reuses this Marshaller's logic to produce a new Marshaller from another type `C` which overrides
   * the [[pekko.http.scaladsl.model.MediaType]] of the marshalling result with the given one.
   * Note that not all wrappings are legal. f the underlying [[pekko.http.scaladsl.model.MediaType]] has constraints with regard to the
   * charsets it allows the new [[pekko.http.scaladsl.model.MediaType]] must be compatible, since pekko-http will never recode entities.
   * If the wrapping is illegal the [[scala.concurrent.Future]] produced by the resulting marshaller will contain a [[RuntimeException]].
   */
  def wrapWithEC[C, D >: B](newMediaType: MediaType)(f: ExecutionContext => C => A)(
      implicit cto: ContentTypeOverrider[D]): Marshaller[C, D] =
    Marshaller { implicit ec => value =>
      import Marshalling._
      this(f(ec)(value)).fast.map {
        _.map {
          (_, newMediaType) match {
            case (WithFixedContentType(_, marshal), newMT: MediaType.Binary) =>
              WithFixedContentType(newMT, () => cto(marshal(), newMT))
            case (WithFixedContentType(oldCT: ContentType.Binary, marshal), newMT: MediaType.WithFixedCharset) =>
              WithFixedContentType(newMT, () => cto(marshal(), newMT))
            case (WithFixedContentType(oldCT: ContentType.NonBinary, marshal), newMT: MediaType.WithFixedCharset)
                if oldCT.charset == newMT.charset =>
              WithFixedContentType(newMT, () => cto(marshal(), newMT))
            case (WithFixedContentType(oldCT: ContentType.NonBinary, marshal), newMT: MediaType.WithOpenCharset) =>
              val newCT = newMT.withCharset(oldCT.charset)
              WithFixedContentType(newCT, () => cto(marshal(), newCT))

            case (WithOpenCharset(oldMT, marshal), newMT: MediaType.WithOpenCharset) =>
              WithOpenCharset(newMT, cs => cto(marshal(cs), newMT.withCharset(cs)))
            case (WithOpenCharset(oldMT, marshal), newMT: MediaType.WithFixedCharset) =>
              WithFixedContentType(newMT, () => cto(marshal(newMT.charset), newMT))

            case (Opaque(marshal), newMT: MediaType.Binary) =>
              WithFixedContentType(newMT, () => cto(marshal(), newMT))
            case (Opaque(marshal), newMT: MediaType.WithFixedCharset) =>
              WithFixedContentType(newMT, () => cto(marshal(), newMT))

            case x => sys.error(
                s"Illegal marshaller wrapping. Marshalling `$x` cannot be wrapped with MediaType `$newMediaType`")
          }
        }
      }
    }

  def compose[C](f: C => A): Marshaller[C, B] =
    Marshaller(implicit ec => c => apply(f(c)))

  def composeWithEC[C](f: ExecutionContext => C => A): Marshaller[C, B] =
    Marshaller(implicit ec => c => apply(f(ec)(c)))
}

//#marshaller-creation
object Marshaller
    extends GenericMarshallers
    with PredefinedToEntityMarshallers
    with PredefinedToResponseMarshallers
    with PredefinedToRequestMarshallers {

  /**
   * Creates a [[Marshaller]] from the given function.
   */
  def apply[A, B](f: ExecutionContext => A => Future[List[Marshalling[B]]]): Marshaller[A, B] =
    new Marshaller[A, B] {
      def apply(value: A)(implicit ec: ExecutionContext) =
        try f(ec)(value)
        catch { case NonFatal(e) => FastFuture.failed(e) }
    }

  /**
   * Helper for creating a [[Marshaller]] using the given function.
   */
  def strict[A, B](f: A => Marshalling[B]): Marshaller[A, B] =
    Marshaller { _ => a => FastFuture.successful(f(a) :: Nil) }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/apache/incubator-pekko-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](marshallers: Marshaller[A, B]*): Marshaller[A, B] =
    Marshaller { implicit ec => a => FastFuture.sequence(marshallers.map(_(a))).fast.map(_.flatten.toList) }

  /**
   * Helper for creating a "super-marshaller" from a number of values and a function producing "sub-marshallers"
   * from these values. Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/apache/incubator-pekko-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[T, A, B](values: T*)(f: T => Marshaller[A, B]): Marshaller[A, B] =
    oneOf(values.map(f): _*)

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a fixed charset from the given function.
   */
  def withFixedContentType[A, B](contentType: ContentType)(marshal: A => B): Marshaller[A, B] =
    new Marshaller[A, B] {
      def apply(value: A)(implicit ec: ExecutionContext) =
        try FastFuture.successful {
            Marshalling.WithFixedContentType(contentType, () => marshal(value)) :: Nil
          }
        catch {
          case NonFatal(e) => FastFuture.failed(e)
        }

      override def compose[C](f: C => A): Marshaller[C, B] =
        Marshaller.withFixedContentType(contentType)(marshal.compose(f))
    }

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a negotiable charset from the given function.
   */
  def withOpenCharset[A, B](mediaType: MediaType.WithOpenCharset)(marshal: (A, HttpCharset) => B): Marshaller[A, B] =
    new Marshaller[A, B] {
      def apply(value: A)(implicit ec: ExecutionContext) =
        try FastFuture.successful {
            Marshalling.WithOpenCharset(mediaType, charset => marshal(value, charset)) :: Nil
          }
        catch {
          case NonFatal(e) => FastFuture.failed(e)
        }

      override def compose[C](f: C => A): Marshaller[C, B] =
        Marshaller.withOpenCharset(mediaType)((c: C, hc: HttpCharset) => marshal(f(c), hc))
    }

  /**
   * Helper for creating a synchronous [[Marshaller]] to non-negotiable content from the given function.
   */
  def opaque[A, B](marshal: A => B): Marshaller[A, B] =
    strict { value => Marshalling.Opaque(() => marshal(value)) }

  /**
   * Helper for creating a [[Marshaller]] combined of the provided `marshal` function
   * and an implicit Marshaller which is able to produce the required final type.
   */
  def combined[A, B, C](marshal: A => B)(implicit m2: Marshaller[B, C]): Marshaller[A, C] =
    Marshaller[A, C] { ec => a => m2.compose(marshal).apply(a)(ec) }
}
//#marshaller-creation

//#marshalling
/**
 * Describes one possible option for marshalling a given value.
 */
sealed trait Marshalling[+A] {
  def map[B](f: A => B): Marshalling[B]

  /**
   * Converts this marshalling to an opaque marshalling, i.e. a marshalling result that
   * does not take part in content type negotiation. The given charset is used if this
   * instance is a `WithOpenCharset` marshalling.
   */
  def toOpaque(charset: HttpCharset): Marshalling[A]
}

object Marshalling {

  /**
   * A Marshalling to a specific [[pekko.http.scaladsl.model.ContentType]].
   */
  final case class WithFixedContentType[A](
      contentType: ContentType,
      marshal: () => A) extends Marshalling[A] {
    def map[B](f: A => B): WithFixedContentType[B] = copy(marshal = () => f(marshal()))
    def toOpaque(charset: HttpCharset): Marshalling[A] = Opaque(marshal)
  }

  /**
   * A Marshalling to a specific [[pekko.http.scaladsl.model.MediaType]] with a flexible charset.
   */
  final case class WithOpenCharset[A](
      mediaType: MediaType.WithOpenCharset,
      marshal: HttpCharset => A) extends Marshalling[A] {
    def map[B](f: A => B): WithOpenCharset[B] = copy(marshal = cs => f(marshal(cs)))
    def toOpaque(charset: HttpCharset): Marshalling[A] = Opaque(() => marshal(charset))
  }

  /**
   * A Marshalling to an unknown MediaType and charset.
   * Circumvents content negotiation.
   */
  final case class Opaque[A](marshal: () => A) extends Marshalling[A] {
    def map[B](f: A => B): Opaque[B] = copy(marshal = () => f(marshal()))
    def toOpaque(charset: HttpCharset): Marshalling[A] = this
  }
}
//#marshalling
