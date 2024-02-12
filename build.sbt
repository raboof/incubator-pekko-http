/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import ValidatePullRequest._
import net.bzzt.reproduciblebuilds.ReproducibleBuildsPlugin.reproducibleBuildsCheckResolver
import com.github.pjfanning.pekkobuild._
import Dependencies.{ h2specExe, h2specName }
import com.typesafe.sbt.MultiJvmPlugin.autoImport._
import java.nio.file.Files
import java.nio.file.attribute.{ PosixFileAttributeView, PosixFilePermission }

import sbtdynver.GitDescribeOutput
import spray.boilerplate.BoilerplatePlugin
import com.lightbend.paradox.apidoc.ApidocPlugin.autoImport.apidocRootPackage

sourceDistName := "apache-pekko-http"
sourceDistIncubating := true

ThisBuild / reproducibleBuildsCheckResolver := Resolver.ApacheMavenStagingRepo

addCommandAlias("verifyCodeStyle", "scalafmtCheckAll; scalafmtSbtCheck; +headerCheckAll; javafmtCheckAll")
addCommandAlias("applyCodeStyle", "+headerCreateAll; scalafmtAll; scalafmtSbt; javafmtAll")

inThisBuild(Def.settings(
  apiURL := {
    val apiVersion = if (isSnapshot.value) "current" else version.value
    Some(url(s"https://pekko.apache.org/api/pekko-http/$apiVersion/"))
  },
  scmInfo := Some(
    ScmInfo(url("https://github.com/apache/incubator-pekko-http"), "git@github.com:apache/incubator-pekko-http.git")),
  description := "Apache Pekko Http: Modern, fast, asynchronous, streaming-first HTTP server and client.",
  testOptions ++= Seq(
    Tests.Argument(TestFrameworks.JUnit, "-q", "-v"),
    Tests.Argument(TestFrameworks.ScalaTest, "-oDF")),
  Dependencies.Versions,
  shellPrompt := { s => Project.extract(s).currentProject.id + " > " },
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  onLoad in Global := {
    sLog.value.info(
      s"Building Pekko HTTP ${version.value} against Pekko ${PekkoCoreDependency.version} on Scala ${(httpCore / scalaVersion).value}")
    (onLoad in Global).value
  },
  projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
  scalafixScalaBinaryVersion := scalaBinaryVersion.value,
  versionScheme := Some(VersionScheme.SemVerSpec)))

// When this is updated the set of modules in Http.allModules should also be updated
lazy val userProjects: Seq[ProjectReference] = List[ProjectReference](
  parsing,
  httpCore,
)
/**
 * Adds a `src/.../scala-2.13+` source directory for Scala 2.13 and newer
 * and a `src/.../scala-2.13-` source directory for Scala version older than 2.13
 */
def add213CrossDirs(config: Configuration): Seq[Setting[_]] = Seq(
  config / unmanagedSourceDirectories += {
    val sourceDir = (config / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((e, n)) if e > 2 || (e == 2 && n >= 13) => sourceDir / "scala-2.13+"
      case _                                            => sourceDir / "scala-2.13-"
    }
  })

val commonSettings =
  add213CrossDirs(Compile) ++
  add213CrossDirs(Test)

val scalaMacroSupport = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 =>
        Seq("-Ymacro-annotations")
      case _ =>
        Seq.empty
    }
  },
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) if n < 13 =>
      Seq(compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full)))
    case _ => Seq.empty
  }))

lazy val parsing = project("parsing")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("pekko.http.parsing"))
  .addPekkoModuleDependency("pekko-actor", "provided", PekkoCoreDependency.default)
  .settings(Dependencies.parsing)
  .settings(scalacOptions += "-language:_")
  .settings(scalaMacroSupport)
  .enablePlugins(ScaladocNoVerificationOfDiagrams)
  .enablePlugins(ReproducibleBuildsPlugin)
  .disablePlugins(MimaPlugin)

lazy val httpCore = project("http-core")
  .settings(commonSettings)
  .settings(AutomaticModuleName.settings("pekko.http.core"))
  .settings(AddMetaInfLicenseFiles.httpCoreSettings)
  .dependsOn(parsing /*, httpScalafixRules % ScalafixConfig*/ )
  .addPekkoModuleDependency("pekko-stream", "provided", PekkoCoreDependency.default)
  .addPekkoModuleDependency(
    "pekko-stream-testkit",
    "test",
    PekkoCoreDependency.default)
  .settings(Dependencies.httpCore)
  .settings(VersionGenerator.versionSettings)
  .settings(scalaMacroSupport)
  .enablePlugins(BootstrapGenjavadoc)
  .enablePlugins(ReproducibleBuildsPlugin)
  .enablePlugins(Pre213Preprocessor).settings(
    Pre213Preprocessor.pre213Files := Seq(
      "headers.scala", "HttpMessage.scala", "LanguageRange.scala", "CacheDirective.scala", "LinkValue.scala"))
  .disablePlugins(ScalafixPlugin)

def project(moduleName: String) =
  Project(id = moduleName, base = file(moduleName)).settings(
    name := s"pekko-$moduleName")

