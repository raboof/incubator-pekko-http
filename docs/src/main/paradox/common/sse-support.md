# Server-Sent Events Support

Server-Sent Events (SSE) is a lightweight and [standardized](https://www.w3.org/TR/eventsource/)
protocol for pushing notifications from an HTTP server to a client. In contrast to WebSocket, which
offers bi-directional communication, SSE only allows for one-way communication from the server to
the client. If that's all you need, SSE has the advantages to be much simpler, to rely on HTTP only
and to offer retry semantics on broken connections by the browser.

According to the SSE specification clients can request an event stream from the server via HTTP. The
server responds with the media type `text/event-stream` which has the fixed character encoding UTF-8
and keeps the response open to send events to the client when available. Events are textual
structures which carry fields and are terminated by an empty line, e.g.

```
data: { "username": "John Doe" }
event: added
id: 42

data: another event
```

Clients can optionally signal the last seen event to the server via the @scala[`Last-Event-ID`]@java[`LastEventId`] header, e.g.
after a reconnect.

## Model

Apache Pekko HTTP represents event streams as @apidoc[Source[ServerSentEvent, \_]] where @apidoc[ServerSentEvent] is a
@scala[case] class with the following read-only properties:

- @scala[`data: String`]@java[`String data`] – the actual payload, may span multiple lines
- @scala[`eventType: Option[String]`]@java[`Optional<String> type`] – optional qualifier, e.g. "added", "removed", etc.
- @scala[`id: Option[String]`]@java[`Optional<String> id`] – optional identifier
- @scala[`retry: Option[Int]`]@java[`OptionalInt retry`] – optional reconnection delay in milliseconds

In accordance to the SSE specification Apache Pekko HTTP also provides the @scala[`Last-Event-ID`]@java[`LastEventId`] header and the
@scala[`text/event-stream`]@java[`TEXT_EVENT_STREAM`] media type.

## Server-side usage: marshalling

In order to respond to an HTTP request with an event stream, you have to
@scala[bring the implicit `ToResponseMarshaller[Source[ServerSentEvent, \_]]` defined by @apidoc[EventStreamMarshalling] into the scope defining the respective route]@java[use the `EventStreamMarshalling.toEventStream` marshaller]:

Scala
:  @@snip [ServerSentEventsExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/ServerSentEventsExampleSpec.scala) { #event-stream-marshalling-example }

Java
:  @@snip [EventStreamMarshallingTest.java](/http-tests/src/test/java/org/apache/pekko/http/javadsl/marshalling/sse/EventStreamMarshallingTest.java) { #event-stream-marshalling-example }

## Client-side usage: unmarshalling

In order to unmarshal an event stream as @apidoc[Source[ServerSentEvent, \_]], you have to @scala[bring the implicit `FromEntityUnmarshaller[Source[ServerSentEvent, _]]` defined by @apidoc[EventStreamUnmarshalling] into scope]@java[use the `EventStreamUnmarshalling.fromEventsStream` unmarshaller]:

Scala
:  @@snip [ServerSentEventsExampleSpec.scala](/docs/src/test/scala/docs/http/scaladsl/ServerSentEventsExampleSpec.scala) { #event-stream-unmarshalling-example }

Java
:  @@snip [EventStreamMarshallingTest.java](/http-tests/src/test/java/org/apache/pekko/http/javadsl/unmarshalling/sse/EventStreamUnmarshallingTest.java) { #event-stream-unmarshalling-example }

Notice that if you are looking for a resilient way to permanently subscribe to an event stream,
Apache Pekko Connectors provides the [EventSource](https://doc.akka.io/docs/alpakka/current/sse.html)
connector which reconnects automatically with the id of the last seen event.
