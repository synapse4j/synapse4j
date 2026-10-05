# Conversations

**English** | [中文](../zh-CN/conversations.md)

The library keeps no conversation state. This page is how a conversation works when you hold it
yourself.

## You hold the request

A conversation is a `ChatRequest` you keep and reuse. Each turn you add the user's message, send
the request, and fold the answer back in:

```java
ChatRequest request = new ChatRequest()
        .systemMessage("You are a helpful assistant.")
        .addUserMessage("Why is the sky blue?");

ChatResponse first = client.chat(request);
client.continueWith(request, first);

request.addUserMessage("And why is it red at sunset?");
ChatResponse second = client.chat(request);
```

`continueWith(request, answer)` does two things: it archives the messages this call sent
(`pendingMessages`) into the conversation so far (`historyMessages`), and appends the assistant's
answer. Call it once per answer you receive. The tool-calling loop calls it for the answers it
consumes; the last answer is yours to fold in.

## History, pending, and the system message

A request carries the conversation in two lists plus one slot:

- `historyMessages` — the conversation as it stands, everything an exchange has already covered;
- `pendingMessages` — the messages this call adds, all of which go out;
- the `systemMessage` slot — the framing, replaced rather than accumulated.

The library never trims either list. Dropping old messages to fit a context window is your
deliberate act, not something the library does behind your back.

## Server-side conversations

Some protocols keep the conversation for you. The OpenAI Responses API, for example, can store each
answer and continue from the previous response id instead of resending the transcript.

This changes what the client sends, not how you write the code: you still call `chat` and
`continueWith` the same way. A client whose protocol is stateful overrides `continueWith` to write
the previous response id into the request's options extras. That id anchors the chain: once it is
there, the next `chat` sends only the pending messages plus the id — the history stays in the
request as your local record, but the server, which already holds it, is not sent it again. The
override also decides which list each answer belongs in. An answer the server stored is archived
into the history as usual and moves the id forward; an answer the server did not store, while an id
is on the request, joins the pending messages instead — archived into the history it would be
skipped on every later call, since an anchored call suppresses exactly that part of the transcript.

The OpenAI Responses client is `OpenAiResponsesChatClient`, built from the same three pieces as in
[Getting started](getting-started.md#2-build-a-client):

```java
import io.github.synapse4j.openai.OpenAiResponsesChatClient;

OpenAiConfig config = new OpenAiConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
config.setStoreResponses(true);

ChatClient client = new OpenAiResponsesChatClient(http, codec, config);
```

Whether the server stores the answer is a decision for each call. The Responses client reads
it from the `storeResponses` option on `OpenAiConfig`, and a call can also set the `store` member
itself through its options extras — a `store` path there lands as the top-level `store` member of
the request and overrides `storeResponses` for that call; left unset either way, the endpoint's
own default stands.

## Persisting a conversation

If you store conversations yourself — a database, a file — implement a `ChatCustomizer` and write
the request and response out when its hooks fire:

```java
client.addChatCustomizer(new ChatCustomizer() {
    @Override
    public void customizeRequest(ChatClient client, ChatRequest request) {
        // runs before each send
    }

    @Override
    public void customizeResponse(ChatClient client, ChatResponse response) {
        // runs on each answer
    }
});
```

A customizer has one hook per step — `customizeRequest`, `customizeResponse`,
`customizeStreamEvent` (one call per `ChatStreamEvent` of a streamed answer) — and does nothing at
the ones you leave alone. Each hook is handed the value the client already holds and changes it in
place. Register it on a [`ToolCallingChatClient`](tools.md#running-the-calls) to see every round of
a tool-calling loop: the registration is handed to the client it wraps, so each hook runs once per
round.

## Moving between providers

A conversation can be continued with a different client — a different model, or a different
provider. The messages are the same types either way. Provider-specific fields in the extras bags
travel as they were read; whether the new provider understands them is your decision, and filtering
them out is yours to do. See [Design and trade-offs](design.md).
