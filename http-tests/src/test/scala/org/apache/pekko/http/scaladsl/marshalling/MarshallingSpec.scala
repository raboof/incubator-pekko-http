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

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.http.impl.util._
import pekko.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import pekko.http.scaladsl.model.HttpCharsets._
import pekko.http.scaladsl.model.MediaTypes._
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers._
import pekko.http.scaladsl.testkit.MarshallingTestUtils
import pekko.stream.ActorMaterializer
import pekko.stream.scaladsl.Source
import pekko.testkit.TestKit
import pekko.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable
import scala.collection.immutable.ListMap
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class MarshallingSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with MultipartMarshallers
    with MarshallingTestUtils {
  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  override val testConfig = ConfigFactory.load()

  "The PredefinedToEntityMarshallers" - {
    "StringMarshaller should marshal strings to `text/plain` content in UTF-8" in {
      marshal("Ha“llo") shouldEqual HttpEntity("Ha“llo")
    }
    "DoneMarshaller should enable marshalling of org.apache.pekko.Done" in {
      marshal(pekko.Done) shouldEqual HttpEntity("")
    }
    "CharArrayMarshaller should marshal char arrays to `text/plain` content in UTF-8" in {
      marshal("Ha“llo".toCharArray) shouldEqual HttpEntity("Ha“llo")
    }
    "FormDataMarshaller should marshal FormData instances to application/x-www-form-urlencoded content" in {
      marshal(FormData(Map("name" -> "Bob", "pass" -> "hällo", "admin" -> ""))) shouldEqual
      HttpEntity(`application/x-www-form-urlencoded`, "name=Bob&pass=h%C3%A4llo&admin=")
    }
  }

  "The PredefinedToResponseMarshallers" - {
    "fromStatusCode should properly marshal a status code that doesn't allow an entity" in {
      marshalToResponse(StatusCodes.NoContent) shouldEqual HttpResponse(StatusCodes.NoContent,
        entity = HttpEntity.Empty)

      // no content-type negotiation for status code marshalling
      marshalToResponseForRequestAccepting(StatusCodes.NoContent, MediaTypes.`application/json`) shouldEqual
      HttpResponse(StatusCodes.NoContent, entity = HttpEntity.Empty)
    }
    "fromStatusCode should properly marshal a status code with a default message" in {
      marshalToResponse(StatusCodes.EnhanceYourCalm) shouldEqual
      HttpResponse(StatusCodes.EnhanceYourCalm, entity = HttpEntity(StatusCodes.EnhanceYourCalm.defaultMessage))

      // no content-type negotiation for status code marshalling
      marshalToResponseForRequestAccepting(StatusCodes.EnhanceYourCalm, MediaTypes.`application/json`) shouldEqual
      HttpResponse(StatusCodes.EnhanceYourCalm, entity = HttpEntity(StatusCodes.EnhanceYourCalm.defaultMessage))
    }
    val headers: immutable.Seq[HttpHeader] = RawHeader("X-Test", "test") :: Nil
    "fromStatusCodeAndHeaders should properly marshal for a status code that doesn't allow an entity" in {
      marshalToResponse(StatusCodes.NoContent -> headers) shouldEqual
      HttpResponse(StatusCodes.NoContent, headers = headers, entity = HttpEntity.Empty)

      // no content-type negotiation for status code marshalling
      marshalToResponseForRequestAccepting(StatusCodes.NoContent -> headers, MediaTypes.`application/json`) shouldEqual
      HttpResponse(StatusCodes.NoContent, headers = headers, entity = HttpEntity.Empty)
    }
    "fromStatusCodeAndHeaders should properly marshal for a status code with a default message" in {
      marshalToResponse(StatusCodes.EnhanceYourCalm -> headers) shouldEqual
      HttpResponse(StatusCodes.EnhanceYourCalm, headers = headers,
        entity = HttpEntity(StatusCodes.EnhanceYourCalm.defaultMessage))

      // no content-type negotiation for status code marshalling
      marshalToResponseForRequestAccepting(StatusCodes.EnhanceYourCalm -> headers,
        MediaTypes.`application/json`) shouldEqual
      HttpResponse(StatusCodes.EnhanceYourCalm, headers = headers,
        entity = HttpEntity(StatusCodes.EnhanceYourCalm.defaultMessage))
    }
    "fromStatusCodeAndHeadersAndValue should properly marshal for a status code that doesn't allow an entity" in {
      marshalToResponse((StatusCodes.NoContent, "This Content was intentionally left blank.")) shouldEqual
      HttpResponse(StatusCodes.NoContent, entity = HttpEntity.Empty)
    }
    "fromStatusCodeAndHeadersAndValue should properly marshal a status code with a default message" in {
      marshalToResponse((StatusCodes.EnhanceYourCalm, "Patience, young padawan!")) shouldEqual
      HttpResponse(StatusCodes.EnhanceYourCalm, entity = HttpEntity("Patience, young padawan!"))
    }
  }

  "The GenericMarshallers" - {
    "optionMarshaller should enable marshalling of Option[T]" in {

      marshal(Some("Ha“llo")) shouldEqual HttpEntity("Ha“llo")
      marshal(None: Option[String]) shouldEqual HttpEntity.Empty
    }
    "eitherMarshaller should enable marshalling of Either[A, B]" in {
      marshal[Either[Array[Char], String]](Right("right")) shouldEqual HttpEntity("right")
      marshal[Either[Array[Char], String]](Left("left".toCharArray)) shouldEqual HttpEntity("left")
    }
  }

  "The MultipartMarshallers" - {
    "multipartMarshaller should correctly marshal multipart content with" - {
      "no parts" in {
        marshal(Multipart.General(`multipart/mixed`)) shouldEqual HttpEntity(
          contentType = `multipart/mixed`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""
                      |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }
      "one empty part" in {
        marshal(Multipart.General(`multipart/mixed`, Multipart.General.BodyPart.Strict(""))) shouldEqual HttpEntity(
          contentType = `multipart/mixed`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""--$randomBoundaryValue
                      |Content-Type: text/plain; charset=UTF-8
                      |
                      |
                      |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }
      "one non-empty part" in {
        marshal(Multipart.General(`multipart/alternative`,
          Multipart.General.BodyPart.Strict(
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            headers =
              `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")) :: Nil))) shouldEqual
        HttpEntity(
          contentType = `multipart/alternative`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""--$randomBoundaryValue
                        |Content-Type: text/plain; charset=UTF-8
                        |Content-Disposition: form-data; name="email"
                        |
                        |test@there.com
                        |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }
      "two different parts" in {
        marshal(Multipart.General(
          `multipart/related`,
          Multipart.General.BodyPart.Strict(HttpEntity(`text/plain`.withCharset(`US-ASCII`),
            "first part, with a trailing linebreak\r\n")),
          Multipart.General.BodyPart.Strict(
            HttpEntity(`application/octet-stream`, ByteString("filecontent")),
            RawHeader("Content-Transfer-Encoding", "binary") :: Nil))) shouldEqual
        HttpEntity(
          contentType = `multipart/related`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""--$randomBoundaryValue
                      |Content-Type: text/plain; charset=US-ASCII
                      |
                      |first part, with a trailing linebreak
                      |
                      |--$randomBoundaryValue
                      |Content-Type: application/octet-stream
                      |Content-Transfer-Encoding: binary
                      |
                      |filecontent
                      |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }
    }

    "multipartFormDataMarshaller should correctly marshal 'multipart/form-data' content with" - {
      "two fields" in {
        marshal(Multipart.FormData(ListMap(
          "surname" -> HttpEntity("Mike"),
          "age" -> marshal(<int>42</int>)))) shouldEqual
        HttpEntity(
          contentType = `multipart/form-data`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""--$randomBoundaryValue
                      |Content-Type: text/plain; charset=UTF-8
                      |Content-Disposition: form-data; name="surname"
                      |
                      |Mike
                      |--$randomBoundaryValue
                      |Content-Type: text/xml; charset=UTF-8
                      |Content-Disposition: form-data; name="age"
                      |
                      |<int>42</int>
                      |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }

      "two fields having a custom `Content-Disposition`" in {
        marshal(Multipart.FormData(Source(List(
          Multipart.FormData.BodyPart("attachment[0]",
            HttpEntity(`text/csv`.withCharset(`UTF-8`), "name,age\r\n\"John Doe\",20\r\n"),
            Map("filename" -> "attachment.csv")),
          Multipart.FormData.BodyPart("attachment[1]", HttpEntity("naice!".getBytes),
            Map("filename" -> "attachment2.csv"),
            List(RawHeader("Content-Transfer-Encoding", "binary"))))))) shouldEqual
        HttpEntity(
          contentType = `multipart/form-data`.withBoundary(randomBoundaryValue).toContentType,
          data = ByteString(s"""--$randomBoundaryValue
                        |Content-Type: text/csv; charset=UTF-8
                        |Content-Disposition: form-data; filename="attachment.csv"; name="attachment[0]"
                        |
                        |name,age
                        |"John Doe",20
                        |
                        |--$randomBoundaryValue
                        |Content-Type: application/octet-stream
                        |Content-Disposition: form-data; filename="attachment2.csv"; name="attachment[1]"
                        |Content-Transfer-Encoding: binary
                        |
                        |naice!
                        |--$randomBoundaryValue--""".stripMarginWithNewline("\r\n")))
      }
    }
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  protected class FixedRandom extends java.util.Random {
    override def nextBytes(array: Array[Byte]): Unit = {
      val bytes = "my-stable-boundary".getBytes("UTF-8")
      bytes.copyToArray(array, 0, bytes.length)
    }
  }
  override protected val multipartBoundaryRandom = new FixedRandom // fix for stable value
  val randomBoundaryValue = super.randomBoundary()
}
