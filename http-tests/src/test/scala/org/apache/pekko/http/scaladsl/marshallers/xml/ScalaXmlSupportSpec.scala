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

package org.apache.pekko.http.scaladsl.marshallers.xml

import java.io.File
import java.nio.file.Files

import org.xml.sax.SAXParseException

import scala.xml.NodeSeq
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import org.scalatest.Inside
import org.apache.pekko
import pekko.util.ByteString
import pekko.http.scaladsl.testkit.ScalatestRouteTest
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.http.scaladsl.model._
import pekko.testkit._
import MediaTypes._
import pekko.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ScalaXmlSupportSpec extends AnyFreeSpec with Matchers with ScalatestRouteTest with Inside {
  import ScalaXmlSupport._

  "NodeSeqMarshaller should" - {
    "marshal xml snippets to `text/xml` content in UTF-8" in {
      marshal(<employee><nr>Ha“llo</nr></employee>) shouldEqual
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<employee><nr>Ha“llo</nr></employee>")
    }
    "unmarshal `text/xml` content in UTF-8 to NodeSeqs" in {
      Unmarshal(HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>Hällö</int>")).to[NodeSeq].map(
        _.text) should evaluateTo("Hällö")
    }
    "reject `application/octet-stream`" in {
      Unmarshal(HttpEntity(`application/octet-stream`, ByteString("<int>Hällö</int>"))).to[NodeSeq].map(_.text) should
      haveFailedWith(UnsupportedContentTypeException(Some(ContentTypes.`application/octet-stream`),
        nodeSeqContentTypeRanges: _*))
    }

    "don't be vulnerable to XXE attacks" - {
      "parse XML bodies without loading in a related schema" in {
        withTempFile("I shouldn't be there!") { f =>
          val xml = s"""<?xml version="1.0" encoding="ISO-8859-1"?>
                     | <!DOCTYPE foo [
                     |   <!ELEMENT foo ANY >
                     |   <!ENTITY xxe SYSTEM "${f.toURI}">]><foo>hello&xxe;</foo>""".stripMargin

          shouldHaveFailedWithSAXParseException(Unmarshal(HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml)).to[NodeSeq])
        }
      }
      "parse XML bodies without loading in a related schema from a parameter" in {
        withTempFile("I shouldnt be there!") { generalEntityFile =>
          withTempFile {
            s"""<!ENTITY % xge SYSTEM "${generalEntityFile.toURI}">
             |<!ENTITY % pe "<!ENTITY xxe '%xge;'>">""".stripMargin
          } { parameterEntityFile =>
            val xml = s"""<?xml version="1.0" encoding="ISO-8859-1"?>
                       | <!DOCTYPE foo [
                       |   <!ENTITY % xpe SYSTEM "${parameterEntityFile.toURI}">
                       |   %xpe;
                       |   %pe;
                       |   ]><foo>hello&xxe;</foo>""".stripMargin
            shouldHaveFailedWithSAXParseException(Unmarshal(HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml)).to[
              NodeSeq])
          }
        }
      }
      "gracefully fail when there are too many nested entities" in {
        val nested = for (x <- 1 to 30)
          yield "<!ENTITY laugh" + x + " \"&laugh" + (x - 1) + ";&laugh" + (x - 1) + ";\">"
        val xml =
          s"""<?xml version="1.0"?>
           | <!DOCTYPE billion [
           | <!ELEMENT billion (#PCDATA)>
           | <!ENTITY laugh0 "ha">
           | ${nested.mkString("\n")}
           | ]>
           | <billion>&laugh30;</billion>""".stripMargin

        shouldHaveFailedWithSAXParseException(Unmarshal(HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml)).to[NodeSeq])
      }
      "gracefully fail when an entity expands to be very large" in {
        val as = "a" * 50000
        val entities = "&a;" * 50000
        val xml = s"""<?xml version="1.0"?>
                  | <!DOCTYPE kaboom [
                  | <!ENTITY a "$as">
                  | ]>
                  | <kaboom>$entities</kaboom>""".stripMargin
        shouldHaveFailedWithSAXParseException(Unmarshal(HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml)).to[NodeSeq])
      }
    }
  }

  def shouldHaveFailedWithSAXParseException(result: Future[NodeSeq]) =
    inside(Await.result(result.failed, 1.second.dilated)) {
      case _: SAXParseException =>
    }

  def withTempFile[T](content: String)(f: File => T): T = {
    val file = File.createTempFile("xxe", ".txt")
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      f(file)
    } finally {
      file.delete()
    }
  }
}
