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
types cross the boundary.

## The client

Extend `AbstractChatClient`. It runs the customizers and the client's own defaults around the
exchange; a subclass supplies the exchange itself:

- `doChat(ChatRequest)` — send the request, read the answer;
- `doStream(ChatRequest)` — open a `ChatStream`.

`AbstractChatClient` hands you `prepare(request)` — the defaults and the request customizers,
applied in place — and `eventPipeline()`, the stream-event customizers ready to run each event
through before it is folded.

When two protocols of one family share their transport flow — the headers, the status handling, the
refusal path — factor it into an abstract base, the way `AbstractOpenAiChatClient` does, and leave
the endpoint, the document and the shape of an answer as the hooks.

## Sending

Build a `HttpRequest` (the library's own, not the JDK's), set its method, headers, body and
per-call `HttpOptions`, and hand it to the `HttpClient`:

```java
void send(ChatRequest request) throws IOException {
    HttpRequest httpRequest = new HttpRequest(baseUrl + endpoint);
    httpRequest.setMethod(HttpRequest.POST);
    httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
    httpRequest.setBody(out -> {
        try (JsonWriter writer = codec.writer(out)) {
            write(request, writer);
        }
    });

    try (HttpResponse response = http.send(httpRequest)) {
        // read the status before the body
    }
}
```

The body is a `HttpBody`: a lambda that writes when the transport asks, so the document goes
straight to the wire instead of being built as a tree first. A second write produces the same bytes,
because the transport may write it again on a retry or a redirect.

Read the status before the body — the body is a stream and can be read once. A non-2xx answer is a
refusal: read its detail and throw a `SynapseHttpException`.

## Writing the document

Write the protocol's own members with `JsonWriter`, token by token. Two rules:

- a member whose value is not set is never emitted;
- the node's `ProviderExtras` is merged over the members you wrote, so a field the application set
  passes through under the provider's own name.

```java
Map<String, Object> members = new LinkedHashMap<>();
if (options.getModel() != null) {
    members.put("model", options.getModel());
}
// ... the members this protocol models, each one only when its value is set ...
options.getExtras().mergeInto(members);
```

## Reading the answer

Walk the body with `JsonReader`, token by token, into a `ChatResponse`. Every field you do not model
goes into the extras of the node it came from — that is what lets an unmodeled field survive a round
trip.

## Streaming

Ask for a stream with the protocol's own member, then read the response's `SseEventStream` — one
`SseEvent` per frame — and map each frame to a `ChatStreamEvent` carrying the protocol's `eventType`
and a normalized `delta`. `DefaultChatStream` folds the events into the aggregated answer; build it
with your iterator, the `BiConsumer<ChatResponse, ChatStreamEvent>` that folds one event into the
answer, and a close action that releases the response.

An event with no normalized content leaves `delta` null and keeps its payload in `extras`.

## Failing loudly

Only one thing fails the call: a requirement on the shape of the answer that the protocol cannot
honour. An answer that violates what was asked for but looks like success reads to the caller as a
model that ignored its instructions — the most expensive kind of wrong — so the module refuses the
call instead of letting it through. Everything else the protocol has no member for — a knob, a
field, a mode — is left unsent, and the call goes on with what the protocol can carry. Refusing
those would break the same application code the moment a provider is swapped, which is what the
shared model exists to prevent.

## Provider spellings as configuration

Where a protocol names a common field differently from its siblings — `max_completion_tokens`
against `max_tokens`, `reasoning` against `reasoning_content` — that name is a field on the module's
config, used for reading and writing alike, with a default that suits the provider. Never infer it
from what a response happened to contain: a conversation that reads one spelling and writes another
renames a member the endpoint never sent.
