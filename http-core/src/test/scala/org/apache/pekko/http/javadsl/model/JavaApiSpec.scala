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

package org.apache.pekko.http.javadsl.model

import java.util.Optional

import org.apache.pekko.japi.Pair

import scala.collection.JavaConverters._
import scala.collection.mutable
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class JavaApiSpec extends AnyFreeSpec with Matchers {
  "The Java API should work for" - {
    "work with Uris" - {
      "query" in {
        Uri.create("/abc")
          .query(Query.create(Pair.create("name", "paul"))) must be(Uri.create("/abc?name=paul"))
      }
      "query(Iterable)" in {
        Uri.create("/abc")
          .query(Query.create(Iterable(Pair.create("name", "tom")).asJava)) must be(Uri.create("/abc?name=tom"))
      }
      "addSegment" in {
        Uri.create("/abc")
          .addPathSegment("def") must be(Uri.create("/abc/def"))

        Uri.create("/abc/")
          .addPathSegment("def") must be(Uri.create("/abc/def"))
      }
      "scheme/host/port" in {
        Uri.create("/abc")
          .scheme("http")
          .host("example.com")
          .port(8258) must be(Uri.create("http://example.com:8258/abc"))
      }
      "toRelative" in {
        Uri.create("http://example.com/abc")
          .toRelative must be(Uri.create("/abc"))
      }
      "pathSegments" in {
        (Uri.create("/abc/def/ghi/jkl")
          .pathSegments().asScala.toSeq must contain).inOrderOnly("abc", "def", "ghi", "jkl")
      }
      "access parameterMap" in {
        (Uri.create("/abc?name=blub&age=28")
          .query().toMap.asScala must contain).allOf("name" -> "blub", "age" -> "28")
      }
      "access parameters" in {
        val mutable.Seq(param1, param2, param3) =
          Uri.create("/abc?name=blub&age=28&name=blub2")
            .query().toList.asScala.map(_.toScala)

        param1 must be("name" -> "blub")
        param2 must be("age" -> "28")
        param3 must be("name" -> "blub2")
      }
      "access single parameter" in {
        val query = Uri.create("/abc?name=blub").query()
        query.get("name") must be(Optional.of("blub"))
        query.get("age") must be(Optional.empty())

        Uri.create("/abc?name=blub&name=blib").query.get("name") must be(Optional.of("blub"))
      }
    }
  }
}
