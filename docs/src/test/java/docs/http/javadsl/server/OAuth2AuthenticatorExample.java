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

package docs.http.javadsl.server;

import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.headers.Host;
import org.apache.pekko.http.javadsl.server.directives.SecurityDirectives.ProvidedCredentials;
import org.apache.pekko.http.javadsl.testkit.JUnitRouteTest;
import org.apache.pekko.http.scaladsl.model.headers.Authorization;

import java.util.Optional;

import org.junit.Test;

//#oauth2-authenticator-java
import org.apache.pekko.http.javadsl.server.Route;

import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.authenticateOAuth2;

//#oauth2-authenticator-java

public class OAuth2AuthenticatorExample extends JUnitRouteTest {

    private final String hardcodedToken = "token";
    
    private Optional<String> authenticate(Optional<ProvidedCredentials> creds) {
        // this is where your actual authentication logic would go, looking up the user
        // based on the token or something in that direction
        
        // We will not allow anonymous access.
        return creds
            .filter(c -> c.verify(hardcodedToken))  // 
            .map(c -> c.identifier());              // Provide the "identifier" down to the inner route
                                                    // (for OAuth2, that's actually just the token)
    }

    @Test
    public void testOAuth2Authenticator() {
        //#oauth2-authenticator-java
        final Route route =
                authenticateOAuth2("My realm", this::authenticate, token ->
                    complete("The secret token is: " + token)
                );


        // tests:
        final HttpRequest okRequest =
            HttpRequest
                .GET("http://pekko.apache.org/")
                .addHeader(Host.create("pekko.apache.org"))
                .addHeader(Authorization.oauth2("token"));
        testRoute(route).run(okRequest).assertEntity("The secret token is: token");

        final HttpRequest badRequest =
                HttpRequest
                        .GET("http://pekko.apache.org/")
                        .addHeader(Host.create("pekko.apache.org"))
                        .addHeader(Authorization.oauth2("wrong"));
        testRoute(route).run(badRequest).assertStatusCode(401);

        //#oauth2-authenticator-java
    }


}