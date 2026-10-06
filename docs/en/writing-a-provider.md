# Writing a provider

**English** | [中文](../zh-CN/writing-a-provider.md)

A provider module translates the shared model to one protocol and back. This page is the shape of
one.

## What a provider is

Two parts, in one module:

- a **wire model** — the protocol's own JSON, written and read token by token;
- an **adapter** — the client that maps the shared `ChatRequest` and `ChatResponse` to that wire
  model and back.

The wire model never leaks into the core: it lives in the provider's package, and only the shared
types cross the boundary. In code it is the token-by-token write and read methods — plus the
per-frame event-type constants streaming uses — not a class hierarchy: the examples below write the
shared request straight to a `JsonWriter` and read the answer straight off a `JsonReader`.

## The client

Extend `AbstractChatClient`. It runs the customizers and the client's own defaults around the
exchange; a subclass supplies the exchange itself:

- `doChat(ChatRequest)` — send the request, read the answer;
- `doStream(ChatRequest)` — open a `ChatStream`.

Everything below is one small class, `MyChatClient`, with its module's config beside it. The base
class requires exactly two methods — `doChat` and `doStream`; its constructor takes nothing, and
customizers, default options and tools attach after construction, through its methods:

```java
public final class MyChatClient extends AbstractChatClient {

    private static final String ENDPOINT = "/v1/chat"; // the path this protocol sits at

    private final HttpClient http;
    private final JsonCodec codec;
    private final MyConfig config;

    public MyChatClient(HttpClient http, JsonCodec codec, MyConfig config) {
        this.http = http;
        this.codec = codec;
        this.config = config;
    }
}
```

The config is a plain class of the module's own — the real ones, `OpenAiConfig` and
`AnthropicConfig`, extend nothing — carrying at least the base URL and the API key:

```java
public class MyConfig {

    private String baseUrl = "https://api.example.com";
    private @Nullable String apiKey;

    // getters and setters; the real configs are Lombok @Data classes
}
```

The examples build the request URL as `config.getBaseUrl() + ENDPOINT`. When a family grows a
second protocol, that path is the thing to promote into a hook — the OpenAI family's abstract base
makes it an abstract `endpoint()` method each protocol answers.

Before either hook runs, the base class has already prepared the request: it applies the client's
own defaults and then every registered request customizer, in place on the very instance the caller
passed, and hands that prepared request to `doChat` or `doStream`. A subclass never calls
`prepare` itself — running it again would apply the customizers twice. For the stream a hook
builds, the base class expects every event to pass through `eventPipeline()` — the stream-event
customizers as one `Consumer<ChatStreamEvent>` — between its source and its fold: hand it to your
`DefaultChatStream` and it runs there, not in your iterator.

A protocol that stores the conversation server-side — the OpenAI Responses API, for example —
changes one more thing: the client overrides `continueWith` to record what its next call needs
instead of archiving the transcript. See
[Server-side conversations](conversations.md#server-side-conversations).

When two protocols of one family share their transport flow — the headers, the status handling, the
refusal path — factor it into an abstract base, the way `AbstractOpenAiChatClient` does, and leave
the endpoint, the document and the shape of an answer as the hooks.

## Sending

Both hooks send the same request — only the document differs — so the construction is one helper
the class shares. Build the library's own `HttpRequest` (not the JDK's), set its method, headers,
body and per-call `HttpOptions`:

```java
HttpRequest buildRequest(ChatRequest request, boolean streaming) {
    HttpRequest httpRequest = new HttpRequest(config.getBaseUrl() + ENDPOINT);
    httpRequest.setMethod(HttpRequest.POST);
    httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
    if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
        httpRequest.getHeaders().put("Authorization", List.of("Bearer " + config.getApiKey()));
    }
    // applied last, so a caller's header wins over any of the module's own
    request.getOptions().getHeaders().forEach((name, value) -> httpRequest.getHeaders()
            .put(name, List.of(value)));
    httpRequest.setOptions(request.getOptions().getHttpOptions());
    httpRequest.setBody(out -> {
        try (JsonWriter writer = codec.writer(out)) {
            write(request, writer, streaming);
        }
    });
    return httpRequest;
}
```

Then sending is one call — hand the built request to the `HttpClient`:

```java
try (HttpResponse response = http.send(buildRequest(request, false))) {
    // read the status before the body
}
```

The call's own `HttpOptions` — its per-call HTTP settings: a response timeout, a body-write mode, an
SSE frame budget — travel to the transport on the request; unset, they are `null` and the
transport's own defaults stand. See [Per-call HTTP settings](customizing.md#per-call-http-settings). Authentication is a
header like any other: the module sets it from the API key on its config when one is configured —
unset or blank, no auth header goes out, which the servers that speak this protocol without
authenticating rely on. The header name and scheme are the protocol's own (`Authorization` bearing
a `Bearer` token for OpenAI, `x-api-key` for Anthropic). The call's own headers come last, from
`request.getOptions().getHeaders()`: copied into the same header map, so a caller's entry replaces
the module's and wins.

The body is a `HttpBody`: a lambda that writes when the transport asks, so the document is produced
on demand instead of being built as a tree first. Whether the bytes are sent as they come or
gathered first is the transport's choice, governed by the call's `bodyWriteMode` — `"auto"`, the
default, lets each transport pick the mode it does best: the JDK transport gathers every written
body and refuses `"streamed"`, while Apache and the RestClient stream it. A second write produces
the same bytes, because the transport may write it again on a retry or a redirect. See
[Per-call HTTP settings](customizing.md#per-call-http-settings) for the modes.

Read the status before the body — the body is a stream and can be read once. A non-2xx answer is a
refusal: read its detail and throw a `SynapseHttpException`. `http.send` itself answers unchecked: a
call that never got an answer — DNS, connect, TLS, a read timeout — arrives as a
`SynapseException`. The checked `IOException` on the fragment above is the response's close in
try-with-resources, the one checked moment a blocking exchange has; the complete `doChat` below
shows where it is wrapped. A complete `doChat` putting these steps together with the document walk
and the answer walk appears at the end of
[Reading the answer](#reading-the-answer).

## Writing the document

Write the protocol's own members, token by token or assembled into a map — a map goes out through
`JsonWriter.writeValue`, which writes it as the object. Two rules:

- a member whose value is not set is never emitted;
- the node's `ProviderExtras` is merged over the members you wrote, so a field the application set
  passes through under the provider's own name.

```java
void write(ChatRequest request, JsonWriter writer, boolean streaming) {
    ChatOptions options = request.getOptions();
    Map<String, Object> members = new LinkedHashMap<>();
    if (options.getModel() != null) {
        members.put("model", options.getModel());
    }
    // ... the members this protocol models, each one only when its value is set ...
    if (streaming) {
        // this protocol asks for a stream with a member of the document
        members.put("stream", true);
    }
    options.getExtras().mergeInto(members);
    writer.writeValue(members);
}
```

This `write` is the one `buildRequest` writes the body with: `doChat` calls it with `false`,
`doStream` with `true`.

## Reading the answer

Walk the body with `JsonReader`, token by token, into a `ChatResponse`. The reader opens on the
response's body stream — the counterpart of the writer the request document was built with:

```java
try (JsonReader reader = codec.reader(response.getBody())) {
    ChatResponse answer = readAnswer(reader);
}
```

The walk is a field-name loop: `nextToken()` advances the reader, `name()` takes each member, and
the token after a member's name carries its value. A member you model is read off that token; one
you do not model is kept rather than dropped — `captureValue()` reads a value of any shape into its
decoded form (a map, a list, a string, a number, a boolean, or null) and hands it to the extras of
the node it came from, under the name it arrived with:

```java
ChatResponse readAnswer(JsonReader reader) {
    if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
        throw new SynapseException("the answer was not a JSON object");
    }
    ChatResponse response = new ChatResponse();
    while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
        String field = reader.name();
        reader.nextToken();
        switch (field) {
            case "id" -> response.setId(reader.string());
            // ... the members this protocol models, each read off the current token ...
            default -> response.getExtras().put(field, reader.captureValue());
        }
    }
    return response;
}
```

That `default` branch is the pass-through rule, and it holds per node: `captureValue()` keeps an
unmodeled field on the node it came from, and when that node goes out again its extras are merged
over the members the writer emits. It is a real round trip for a message: `continueWith` archives
the answered message into the conversation (see [Conversations](conversations.md)), and the next
call's document walk merges the message's extras into its entry — the same merge the request
options' own extras get in [Writing the document](#writing-the-document). A field kept on the response itself
travels nowhere: a response is never sent, nothing copies its extras onward, and it is the caller's
to read.

The fragments of this page add up to one blocking exchange. The request `doChat` receives has
already been through `prepare` — `AbstractChatClient` runs the defaults and the request
customizers before the subclass sees the request — and the `ChatResponse` it returns is the
caller's answer:

```java
@Override
protected ChatResponse doChat(ChatRequest request) {
    HttpRequest httpRequest = buildRequest(request, false);

    try (HttpResponse response = http.send(httpRequest)) {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new SynapseHttpException(readBody(response), status);
        }
        try (JsonReader reader = codec.reader(response.getBody())) {
            return readAnswer(reader);
        }
    } catch (IOException e) {
        throw new SynapseException("the answer could not be read", e);
    }
}
```

`write` is the document walk from the previous section, `readAnswer` the token-by-token walk
described above; `readBody` reads the refusal body. The exchange answers unchecked — a failure
the call could not get an answer through is a `SynapseException`, not a checked condition a
caller must plan around.

## Streaming

The streaming half of the exchange mirrors the blocking one. The document asks for a stream with
the protocol's own member; the response then answers `sseEventStream()` with the frames already cut
for you — one `SseEvent` per frame, parsed from the `text/event-stream` body, `null` when the
answer is not an event stream at all. Your iterator maps each frame to a `ChatStreamEvent`
(`ChatStreamEvent` is the element type the stream hands out, not `SseEvent`), and hands the stream
to `DefaultChatStream`:

```java
@Override
protected ChatStream doStream(ChatRequest request) {
    HttpResponse response = http.send(buildRequest(request, true));
    try {
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new SynapseHttpException(readBody(response), status);
        }
        SseEventStream frames = response.sseEventStream();
        if (frames == null) {
            throw new SynapseException(ENDPOINT + " answered a streamed request without an event stream");
        }
        // On success the response stays open: the stream owns it, and closing the stream
        // is what cancels an answer still in flight.
        return new DefaultChatStream(events(frames), eventPipeline(), MyChatClient::fold, response::close);
    } catch (RuntimeException failure) {
        // Between the response arriving and the stream taking it over, nothing else holds the
        // connection: release it before the failure leaves.
        try (HttpResponse closing = response) {
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        throw failure;
    }
}
```

`fold` is a plain merge of one event into the answer being assembled — static, so it can be a
method reference:

```java
/** Folds one event into the answer being assembled. */
private static void fold(ChatResponse answer, ChatStreamEvent event) {
    if (event.getId() != null) {
        answer.setId(event.getId());
    }
    if (event.getFinishReason() != null) {
        answer.setFinishReason(event.getFinishReason());
    }
    answer.getExtras().putAll(event.getExtras());
    if (event.getDelta() != null) {
        // append the delta's parts to the answer's message, one part type at a time
    }
}
```

The hook answers unchecked, same as `doChat`: `http.send` has no checked failure, and the response's
close — the one checked moment — belongs to the close action, whose failure `DefaultChatStream`
wraps for you. Between the response arriving and the stream taking over, only the refusal body and
the `sseEventStream()` call run here, so the `catch` releases the connection a failure would
otherwise leave held.

The iterator's job is the frame-to-event map. `SseEventStream` is an `Iterator<SseEvent>` whose
`hasNext()` blocks for the next frame, so the mapping is a lazy pass over it; each `SseEvent`
carries the frame's `event:` name (or `null`) and its joined `data:` lines:

```java
private Iterator<ChatStreamEvent> events(SseEventStream frames) {
    return new Iterator<>() {
        public boolean hasNext() {
            return frames.hasNext();
        }

        public ChatStreamEvent next() {
            return toEvent(frames.next());
        }
    };
}

/** Maps one frame to its event. */
private ChatStreamEvent toEvent(SseEvent frame) {
    ChatStreamEvent event = new ChatStreamEvent();
    // null when the protocol names its events inside the payload instead
    event.setEventType(frame.getEvent());
    try (JsonReader reader = codec.reader(new ByteArrayInputStream(frame.getData().getBytes(StandardCharsets.UTF_8)))) {
        // ... the same field-name loop as readAnswer, into the event's delta and extras ...
    } catch (IOException e) {
        throw new SynapseException("a stream frame could not be read", e);
    }
    return event;
}
```

The payload walk is the same field-name loop as `readAnswer`: members you model become the event's
`delta`, and an unmodeled payload member lands in `event.getExtras()` as a parsed value, never as
raw text. Some protocols leave the SSE `event:` name empty and discriminate inside the payload
instead — OpenAI chat completions name each chunk in an `object` member, for example; the type read
there is what `setEventType` receives, kept as written.

`DefaultChatStream` pulls from your iterator, and on each event runs `eventPipeline()` and then the
fold — the `BiConsumer<ChatResponse, ChatStreamEvent>` (`MyChatClient::fold` above) that merges one
event into the aggregated answer — so the stream-event customizers run between your source and your
fold without either calling them. The last constructor argument is the close action,
`response::close` here: it runs once, whether the stream is closed, exhausted or fails, and is what
cancels the connection.

An event with no normalized content leaves `delta` null and keeps its payload in `extras`.

## Failing loudly

Of the things a call asks for that a protocol has no member for, only one fails the call: a
requirement on the shape of the answer that the protocol cannot honour. An answer that violates
what was asked for but looks like success reads to the caller as a model that ignored its
instructions — the most expensive kind of wrong — so the module refuses the call instead of letting
it through. Everything else the protocol has no member for — a knob, a field, a mode — is left
unsent, and the call goes on with what the protocol can carry. Refusing those would break the same
application code the moment a provider is swapped, which is what the shared model exists to
prevent.

## Provider spellings as configuration

Where a protocol names a common field differently from its siblings — `max_completion_tokens`
against `max_tokens`, `reasoning` against `reasoning_content` — that name is a field on the module's
config, used for reading and writing alike, with a default that suits the provider. Never infer it
from what a response happened to contain: a conversation that reads one spelling and writes another
renames a member the endpoint never sent.
