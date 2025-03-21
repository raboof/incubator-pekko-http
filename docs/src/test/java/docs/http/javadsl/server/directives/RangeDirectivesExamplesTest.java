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

import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.Multipart;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.headers.ByteRange;
import org.apache.pekko.http.javadsl.model.headers.ContentRange;
import org.apache.pekko.http.javadsl.model.headers.Range;
import org.apache.pekko.http.javadsl.model.headers.RangeUnits;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.unmarshalling.Unmarshaller;
import org.apache.pekko.http.javadsl.testkit.JUnitRouteTest;
import org.apache.pekko.http.javadsl.testkit.TestRouteResult;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.util.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

//#withRangeSupport
import static org.apache.pekko.http.javadsl.server.Directives.complete;
import static org.apache.pekko.http.javadsl.server.Directives.withRangeSupport;

//#withRangeSupport

public class RangeDirectivesExamplesTest extends JUnitRouteTest {
    @Override
    public Config additionalConfig() {
        return ConfigFactory.parseString("pekko.http.routing.range-coalescing-threshold=2");
    }

    @Test
    public void testWithRangeSupport() {
        //#withRangeSupport
        final Route route = withRangeSupport(() -> complete("ABCDEFGH"));

        // test:
        final String bytes348Range = ContentRange.create(RangeUnits.BYTES,
                org.apache.pekko.http.javadsl.model.ContentRange.create(3, 4, 8)).value();
        final org.apache.pekko.http.javadsl.model.ContentRange bytes028Range =
                org.apache.pekko.http.javadsl.model.ContentRange.create(0, 2, 8);
        final org.apache.pekko.http.javadsl.model.ContentRange bytes678Range =
                org.apache.pekko.http.javadsl.model.ContentRange.create(6, 7, 8);
        final Materializer materializer = systemResource().materializer();

        testRoute(route).run(HttpRequest.GET("/")
                .addHeader(Range.create(RangeUnits.BYTES, ByteRange.createSlice(3, 4))))
                .assertHeaderKindExists("Content-Range")
                .assertHeaderExists("Content-Range", bytes348Range)
                .assertStatusCode(StatusCodes.PARTIAL_CONTENT)
                .assertEntity("DE");

        // we set "pekko.http.routing.range-coalescing-threshold = 2"
        // above to make sure we get two BodyParts
        final TestRouteResult response = testRoute(route).run(HttpRequest.GET("/")
                .addHeader(Range.create(RangeUnits.BYTES,
                        ByteRange.createSlice(0, 1), ByteRange.createSlice(1, 2), ByteRange.createSlice(6, 7))));
        response.assertHeaderKindNotExists("Content-Range");

        final CompletionStage<List<Multipart.ByteRanges.BodyPart>> completionStage =
                response.entity(Unmarshaller.entityToMultipartByteRangesUnmarshaller()).getParts()
                        .runFold(new ArrayList<>(), (acc, n) -> {
                            acc.add(n);
                            return acc;
                        }, materializer);
        try {
            final List<Multipart.ByteRanges.BodyPart> bodyParts =
                    completionStage.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(2, bodyParts.toArray().length);

            final Multipart.ByteRanges.BodyPart part1 = bodyParts.get(0);
            assertEquals(bytes028Range, part1.getContentRange());
            assertEquals(ByteString.fromString("ABC"),
                    part1.toStrict(1000, materializer).toCompletableFuture().get().getEntity().getData());

            final Multipart.ByteRanges.BodyPart part2 = bodyParts.get(1);
            assertEquals(bytes678Range, part2.getContentRange());
            assertEquals(ByteString.fromString("GH"),
                    part2.toStrict(1000, materializer).toCompletableFuture().get().getEntity().getData());

        } catch (Exception e) {
            // please handle this in production code
        }
        //#withRangeSupport
    }
}
