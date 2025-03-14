/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import org.apache.pekko.http.javadsl.model.*;
import org.apache.pekko.http.javadsl.model.headers.ContentType;
import org.apache.pekko.http.javadsl.model.headers.Location;
import org.apache.pekko.http.javadsl.server.Rejections;
import org.apache.pekko.http.javadsl.server.RequestContext;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.server.RouteResult;
import org.apache.pekko.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

//#complete
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.path;

//#complete

//#reject
import org.apache.pekko.http.javadsl.server.Directives;

import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.path;
import static org.apache.pekko.http.javadsl.server.Directives.reject;
//#reject
//#redirect
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.pathEnd;
import static org.apache.pekko.http.javadsl.server.Directives.pathPrefix;
import static org.apache.pekko.http.javadsl.server.Directives.pathSingleSlash;
import static org.apache.pekko.http.javadsl.server.Directives.redirect;
//#redirect
//#failWith
import static org.apache.pekko.http.javadsl.server.Directives.failWith;
import static org.apache.pekko.http.javadsl.server.Directives.path;

//#failWith

public class RouteDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testShowRedirectOnCompleteWithTerms() {
    final StatusCode redirectionType = StatusCodes.FOUND;
    final Uri uri = Uri.create("http://pekko.apache.org");
    final Function<RequestContext, CompletionStage<RouteResult>> route = rc ->
            //#red-impl
            rc.completeWith(HttpResponse.create()
                    .withStatus(redirectionType)
                    .addHeader(Location.create(uri))
            //#red-impl
            );
  }

  @Test
  public void testComplete() {
    //#complete
    final Route route = concat(
      path("a", () -> complete(HttpResponse.create().withEntity("foo"))),
      path("b", () -> complete(StatusCodes.OK)),
      path("c", () -> complete(StatusCodes.CREATED, "bar")),
      path("d", () -> complete(StatusCodes.get(201), "bar")),
      path("e", () ->
        complete(StatusCodes.CREATED,
                 Collections.singletonList(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8)),
                 HttpEntities.create("bar"))),
      path("f", () ->
        complete(StatusCodes.get(201),
                 Collections.singletonList(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8)),
                 HttpEntities.create("bar"))),
      path("g", () -> complete("baz"))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/a"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("foo");

    testRoute(route).run(HttpRequest.GET("/b"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");

    testRoute(route).run(HttpRequest.GET("/c"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/d"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/e"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertHeaderExists(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8))
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/f"))
      .assertStatusCode(StatusCodes.CREATED)
      .assertHeaderExists(ContentType.create(ContentTypes.TEXT_PLAIN_UTF8))
      .assertEntity("bar");

    testRoute(route).run(HttpRequest.GET("/g"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("baz");
    //#complete
  }

  @Test
  public void testReject() {
    //#reject
    final Route route = concat(
      path("a", Directives::reject), // don't handle here, continue on
      path("a", () -> complete("foo")),
      path("b", () -> reject(Rejections.validationRejection("Restricted!")))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/a"))
      .assertEntity("foo");

    runRouteUnSealed(route, HttpRequest.GET("/b"))
      .assertRejections(Rejections.validationRejection("Restricted!"));
    //#reject
  }

  @Test
  public void testRedirect() {
    //#redirect
    final Route route = pathPrefix("foo", () ->
      concat(
        pathSingleSlash(() -> complete("yes")),
        pathEnd(() -> redirect(Uri.create("/foo/"), StatusCodes.PERMANENT_REDIRECT))
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/"))
      .assertEntity("yes");

    testRoute(route).run(HttpRequest.GET("/foo"))
      .assertStatusCode(StatusCodes.PERMANENT_REDIRECT)
      .assertEntity("The request, and all future requests should be repeated using <a href=\"/foo/\">this URI</a>.");
    //#redirect
  }

  @Test
  public void testFailWith() {
    //#failWith
    final Route route = path("foo", () ->
      failWith(new RuntimeException("Oops."))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo"))
      .assertStatusCode(StatusCodes.INTERNAL_SERVER_ERROR)
      .assertEntity("There was an internal server error.");
    //#failWith
  }
}
