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

import java.io.{ ByteArrayInputStream, InputStreamReader }
import javax.xml.parsers.{ SAXParser, SAXParserFactory }
import scala.collection.immutable
import scala.xml.{ NodeSeq, XML }
import org.apache.pekko
import pekko.http.scaladsl.unmarshalling._
import pekko.http.scaladsl.marshalling._
import pekko.http.scaladsl.model._
import MediaTypes._

trait ScalaXmlSupport {
  implicit def defaultNodeSeqMarshaller: ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(ScalaXmlSupport.nodeSeqMediaTypes.map(nodeSeqMarshaller): _*)

  def nodeSeqMarshaller(mediaType: MediaType.NonBinary): ToEntityMarshaller[NodeSeq] =
    Marshaller.StringMarshaller.wrap(mediaType)(_.toString())

  implicit def defaultNodeSeqUnmarshaller: FromEntityUnmarshaller[NodeSeq] =
    nodeSeqUnmarshaller(ScalaXmlSupport.nodeSeqContentTypeRanges: _*)

  def nodeSeqUnmarshaller(ranges: ContentTypeRange*): FromEntityUnmarshaller[NodeSeq] =
    Unmarshaller.byteArrayUnmarshaller.forContentTypes(ranges: _*).mapWithCharset { (bytes, charset) =>
      if (bytes.length > 0) {
        val reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset.nioCharset)
        XML.withSAXParser(createSAXParser()).load(reader): NodeSeq // blocking call! Ideally we'd have a `loadToFuture`
      } else NodeSeq.Empty
    }

  /**
   * Provides a SAXParser for the NodeSeqUnmarshaller to use. Override to provide a custom SAXParser implementation.
   * Will be called once for for every request to be unmarshalled. The default implementation calls `ScalaXmlSupport.createSaferSAXParser`.
   */
  protected def createSAXParser(): SAXParser = ScalaXmlSupport.createSaferSAXParser()
}
object ScalaXmlSupport extends ScalaXmlSupport {
  val nodeSeqMediaTypes: immutable.Seq[MediaType.NonBinary] =
    List(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`)
  val nodeSeqContentTypeRanges: immutable.Seq[ContentTypeRange] = nodeSeqMediaTypes.map(ContentTypeRange(_))

  /** Creates a safer SAXParser. */
  def createSaferSAXParser(): SAXParser = {
    val factory = SAXParserFactory.newInstance()
    import javax.xml.XMLConstants

    // Constants manually imported from com.sun.org.apache.xerces.internal.impl.Constants
    // which isn't accessible any more when scalac option `-release 8` is used.
    val SAX_FEATURE_PREFIX = "http://xml.org/sax/features/"
    val EXTERNAL_GENERAL_ENTITIES_FEATURE = "external-general-entities"
    val EXTERNAL_PARAMETER_ENTITIES_FEATURE = "external-parameter-entities"
    val XERCES_FEATURE_PREFIX = "http://apache.org/xml/features/"
    val DISALLOW_DOCTYPE_DECL_FEATURE = "disallow-doctype-decl"

    factory.setFeature(SAX_FEATURE_PREFIX + EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
    factory.setFeature(SAX_FEATURE_PREFIX + EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
    factory.setFeature(XERCES_FEATURE_PREFIX + DISALLOW_DOCTYPE_DECL_FEATURE, true)
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    val parser = factory.newSAXParser()
    try {
      parser.setProperty("http://apache.org/xml/properties/locale", java.util.Locale.ROOT)
    } catch {
      case e: org.xml.sax.SAXNotRecognizedException => // property is not needed
    }
    parser
  }
}
