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

import scala.concurrent.Future
import org.apache.pekko
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers._
import pekko.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsMissing, CredentialsRejected }
import pekko.testkit.EventFilter

class SecurityDirectivesSpec extends RoutingSpec {
  val dontBasicAuth = authenticateBasicAsync[String]("MyRealm", _ => Future.successful(None))
  val dontOAuth2Auth = authenticateOAuth2Async[String]("MyRealm", _ => Future.successful(None))
  val doBasicAuth = authenticateBasicPF("MyRealm", { case Credentials.Provided(identifier) => identifier })
  val doOAuth2Auth = authenticateOAuth2PF("MyRealm", { case Credentials.Provided(identifier) => identifier })
  val authWithAnonymous = doBasicAuth.withAnonymousUser("We are Legion")

  val basicChallenge = HttpChallenges.basic("MyRealm")
  val oAuth2Challenge = HttpChallenges.oAuth2("MyRealm")

  def doBasicAuthVerify(secret: String) =
    authenticateBasicPF("MyRealm", { case p @ Credentials.Provided(identifier) if p.verify(secret) => identifier })
  def doBasicAuthProvideVerify(secret: String) =
    authenticateBasicPF("MyRealm",
      { case p @ Credentials.Provided(identifier) if p.provideVerify(password => secret == password) => identifier })

  "basic authentication" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontBasicAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, basicChallenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        dontBasicAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, basicChallenge) }
    }
    "reject requests with an OAuth2 Bearer Token Authorization header with 401" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> Route.seal {
        dontOAuth2Auth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontBasicAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "extract the object representing the user identity created by successful authentication" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        doBasicAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created by verifying user password" in {
      val secret = "secret"
      Get() ~> Authorization(BasicHttpCredentials("Alice", secret)) ~> {
        doBasicAuthVerify(secret) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created by providing a custom verifier to test user password" in {
      val secret = "secret"
      Get() ~> Authorization(BasicHttpCredentials("Alice", secret)) ~> {
        doBasicAuthProvideVerify(secret) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        authWithAnonymous { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start =
          "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response.").intercept {
        Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
          Route.seal {
            doBasicAuth { _ => throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
  "bearer token authentication" should {
    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, oAuth2Challenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject unauthenticated requests without Authorization header but with access_token URI parameter with an AuthenticationFailedRejection" in {
      Get("?access_token=myToken") ~> {
        dontOAuth2Auth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, oAuth2Challenge) }
    }
    "reject requests with a Basic Authorization header with 401" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> Route.seal {
        dontBasicAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(basicChallenge))
      }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontOAuth2Auth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(oAuth2Challenge))
      }
    }
    "extract the object representing the user identity created by successful authentication with Authorization header" in {
      Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
        doOAuth2Auth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created by successful authentication with access_token URI parameter" in {
      Get("?access_token=myToken") ~> {
        doOAuth2Auth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "myToken" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        authWithAnonymous { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException("Boom")
      EventFilter[TestException.type](
        occurrences = 1,
        start =
          "Error during processing of request: 'Boom'. Completing with 500 Internal Server Error response.").intercept {
        Get() ~> Authorization(OAuth2BearerToken("myToken")) ~> {
          Route.seal {
            doOAuth2Auth { _ => throw TestException }
          }
        } ~> check { status shouldEqual StatusCodes.InternalServerError }
      }
    }
  }
  "authentication directives" should {
    "properly stack" in {
      val otherChallenge = HttpChallenge("MyAuth", Some("MyRealm2"))
      val otherAuth: Directive1[String] = authenticateOrRejectWithChallenge { (cred: Option[HttpCredentials]) =>
        Future.successful(Left(otherChallenge))
      }
      val bothAuth = dontBasicAuth | otherAuth

      Get() ~> Route.seal(bothAuth { echoComplete }) ~> check {
        status shouldEqual StatusCodes.Unauthorized
        headers.collect {
          case `WWW-Authenticate`(challenge +: Nil) => challenge
        } shouldEqual Seq(basicChallenge, otherChallenge)
      }
    }
  }

  "authorization directives" should {
    "authorize" in {
      Get() ~> {
        authorize(_ => true) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorize" in {
      Get() ~> {
        authorize(_ => false) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }

    "authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ => Future.successful(true)) { complete("OK") }
      } ~> check { responseAs[String] shouldEqual "OK" }
    }
    "not authorizeAsync" in {
      Get() ~> {
        authorizeAsync(_ => Future.successful(false)) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
    "not authorizeAsync when future fails" in {
      Get() ~> {
        authorizeAsync(_ => Future.failed(new Exception("Boom!"))) { complete("OK") }
      } ~> check { rejection shouldEqual AuthorizationFailedRejection }
    }
  }

}
