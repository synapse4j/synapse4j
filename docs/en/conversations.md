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
`continueWith` the same way. A client whose protocol is stateful overrides `continueWith` to record
what its next call needs — the previous response id — and to decide which list the answer belongs
in.

Whether the server stores the answer is a per-call decision. On the OpenAI Responses client it is
the `storeResponses` option on `OpenAiConfig`; left unset, the endpoint's own default stands.

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
`customizeStreamEvent` — and does nothing at the ones you leave alone. Each hook is handed the
value the client already holds and changes it in place. Register one on an inner client to see
every round of a tool-calling loop.

## Moving between providers

A conversation can be continued with a different client — a different model, or a different
provider. The messages are the same types either way. Provider-specific fields in the extras bags
travel as they were read; whether the new provider understands them is your decision, and filtering
them out is yours to do. See [Design and trade-offs](design.md).
