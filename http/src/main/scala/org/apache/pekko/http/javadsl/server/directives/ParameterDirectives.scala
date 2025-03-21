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

package org.apache.pekko.http.javadsl.server.directives

import java.util.AbstractMap.SimpleImmutableEntry
import java.util.function.{ Function => JFunction }
import java.util.{ List => JList, Map => JMap, Optional }

import org.apache.pekko
import pekko.http.javadsl.server.Route
import pekko.http.javadsl.unmarshalling.Unmarshaller
import pekko.http.scaladsl.server.directives.ParameterDirectives._
import pekko.http.scaladsl.server.directives.{ ParameterDirectives => D }

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

abstract class ParameterDirectives extends MiscDirectives {

  def parameter(name: String, inner: java.util.function.Function[String, Route]): Route = RouteAdapter(
    D.parameter(name) { value =>
      inner.apply(value).delegate
    })

  @CorrespondsTo("parameter")
  def parameterOptional(
      name: String, inner: java.util.function.Function[Optional[String], Route]): Route = RouteAdapter(
    D.parameter(name.optional) { value =>
      inner.apply(value.asJava).delegate
    })

  @CorrespondsTo("parameter")
  def parameterRequiredValue[T](t: Unmarshaller[String, T], requiredValue: T, name: String,
      inner: java.util.function.Supplier[Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].requiredValue(requiredValue)) { _ => inner.get.delegate })
  }

  @CorrespondsTo("parameterSeq")
  def parameterList(
      name: String, inner: java.util.function.Function[java.util.List[String], Route]): Route = RouteAdapter(
    D.parameter(name.repeated) { values =>
      inner.apply(values.toSeq.asJava).delegate
    })

  def parameter[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T]) { value =>
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("parameter")
  def parameterOptional[T](t: Unmarshaller[String, T], name: String,
      inner: java.util.function.Function[Optional[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].optional) { value =>
        inner.apply(value.asJava).delegate
      })
  }

  @CorrespondsTo("parameter")
  def parameterOrDefault[T](t: Unmarshaller[String, T], defaultValue: T, name: String,
      inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].withDefault(defaultValue)) { value =>
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("parameterSeq")
  def parameterList[T](t: Unmarshaller[String, T], name: String,
      inner: java.util.function.Function[java.util.List[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].repeated) { values =>
        inner.apply(values.toSeq.asJava).delegate
      })
  }

  def parameterMap(inner: JFunction[JMap[String, String], Route]): Route = RouteAdapter {
    D.parameterMap { map => inner.apply(map.asJava).delegate }
  }

  def parameterMultiMap(inner: JFunction[JMap[String, JList[String]], Route]): Route = RouteAdapter {
    D.parameterMultiMap { map => inner.apply(map.mapValues { l => l.asJava }.toMap.asJava).delegate }
  }

  @CorrespondsTo("parameterSeq")
  def parameterList(inner: JFunction[JList[JMap.Entry[String, String]], Route]): Route = RouteAdapter {
    D.parameterSeq { list =>
      val entries: Seq[JMap.Entry[String, String]] = list.map { e => new SimpleImmutableEntry(e._1, e._2) }
      inner.apply(entries.asJava).delegate
    }
  }

}
