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

package docs

import java.lang.reflect.Method

import org.apache.pekko.annotation.ApiMayChange
import org.reflections.Reflections
import org.reflections.scanners.{ MethodAnnotationsScanner, Scanners, TypeAnnotationsScanner }
import org.reflections.util.{ ClasspathHelper, ConfigurationBuilder }
import org.scalatest.Assertion

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ApiMayChangeDocCheckerSpec extends AnyWordSpec with Matchers {

  def prettifyName(clazz: Class[_]): String = {
    clazz.getCanonicalName.replaceAll("\\$minus", "-").split("\\$")(0)
  }

  // As Specs, Directives and HttpApp inherit get all directives methods, we skip those as they are not really bringing any extra info
  def removeClassesToIgnore(method: Method): Boolean = {
    Seq("Spec", ".Directives", ".HttpApp").exists(method.getDeclaringClass.getCanonicalName.contains)
  }

  def collectMissing(docPage: Seq[String])(set: Set[String], name: String): Set[String] = {
    if (docPage.exists(line => line.contains(name)))
      set
    else
      set + name
  }

  def checkNoMissingCases(missing: Set[String], typeOfUsage: String): Assertion = {
    if (missing.isEmpty) {
      succeed
    } else {
      fail(
        s"Please add the following missing $typeOfUsage annotated with @ApiMayChange to docs/src/main/paradox/compatibility-guidelines.md:\n${missing.map(
            miss => s"* $miss").mkString("\n")}")
    }
  }

  "compatibility-guidelines.md doc page" should {
    val reflections = new Reflections(new ConfigurationBuilder()
      .setUrls(ClasspathHelper.forPackage("org.apache.pekko.http"))
      .setScanners(
        Scanners.TypesAnnotated,
        Scanners.MethodsAnnotated))
    val source = Source.fromFile("docs/src/main/paradox/compatibility-guidelines.md")
    try {
      val docPage = source.getLines().toList
      "contain all ApiMayChange references in classes" in {
        val classes: mutable.Set[Class[_]] = reflections.getTypesAnnotatedWith(classOf[ApiMayChange], true).asScala
        val missing = classes
          .map(prettifyName)
          .foldLeft(Set.empty[String])(collectMissing(docPage))
        checkNoMissingCases(missing, "Types")
      }
      "contain all ApiMayChange references in methods" in {
        val methods = reflections.getMethodsAnnotatedWith(classOf[ApiMayChange]).asScala
        val missing = methods
          .filterNot(removeClassesToIgnore)
          .map(method => prettifyName(method.getDeclaringClass) + "#" + method.getName)
          .foldLeft(Set.empty[String])(collectMissing(docPage))
        checkNoMissingCases(missing, "Methods")
      }
    } finally source.close()

  }
}
