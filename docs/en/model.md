# The call model

**English** | [中文](../zh-CN/model.md)

Every call and every answer is built from the same small set of types, whichever provider is
underneath. This page is what those types are.

## A call and its answer

A call is a `ChatRequest`. It carries:

- the **system message** — the instructions the model answers under, or none;
- the **conversation** — `historyMessages`, everything an exchange has already covered, and
  `pendingMessages`, the messages this call adds;
- the **tools** the model may call;
- the **options** for this call — the model, the temperature, the shape of the answer, and so on;
- an optional **context** tying the call to a conversation.

The answer is a `ChatResponse`. Its `message` is the assistant's turn, as a `ChatMessage` — the
same type a request is built from, so it can be appended to the next request as it stands. Beside
it sit the `finishReason`, the `usage`, the `model` that answered, the provider's `id` for the
response, the response `headers`, and a bag of provider-specific fields.

## Messages

A message has a role and a list of parts. The role is a plain string — `ChatRole` names the common
ones, `system`, `user`, `assistant` and `tool` — so a provider or an application can use a role
the library has never heard of.

```java
ChatMessage.user("Why is the sky blue?");          // a user turn
ChatMessage.system("Answer in one sentence.");     // instructions
ChatMessage.assistant("Because of scattering.");   // a model turn written by hand
```

A message can also carry an `id`, for your own bookkeeping, and provider-specific fields.

## Parts

The content of a message is a list of parts, each one of:

- `TextPart` — text sent to the model or produced by it;
- `ReasoningPart` — the model's reasoning, kept apart from the answer because applications usually
  hide it, bill it separately, or have to send it back unchanged;
- `ToolCallPart` — the model asking to call a tool: a call id, the tool name, and the arguments as
  JSON text;
- `ToolResultPart` — the answer to a tool call: the call id, the tool name, one or more parts, and
  whether the call failed;
- `MediaPart` — an image, audio, video or document.

`ContentPart` is not final: a provider or an application adds a kind the library does not model by
subclassing it.

Most messages are a single text part, and there is a shorthand for adding one:

```java
ChatMessage.user("Hello").addText(", world");
```

## Options

`ChatOptions` holds what a call can be tuned with:

| Field | What it sets |
|---|---|
| `model` | which model to call |
| `temperature`, `topP` | sampling |
| `maxOutputTokens` | an upper bound on generated tokens |
| `reasoningEffort` | how much the model should reason |
| `toolChoice`, `toolChoiceName` | whether, and which, tool the model may call |
| `responseFormat` | prose, JSON, or JSON against a schema |
| `httpOptions`, `headers` | per-call transport settings |
| `extras` | provider-specific fields |

Every field is optional: `null` means "no opinion", and the client's defaults fill the gap. Only
knobs at least two providers agree on live here; a field one provider alone has goes in the extras
bag instead.

## Provider-specific fields

Anything the library does not model is not lost. Every node — a message, a part, a tool, the
options — can carry a `ProviderExtras` bag, a map from a dotted path to a value that is merged into
the outgoing JSON as it stands:

```java
message.getOrCreateExtras().putRaw("thinking.budget_tokens", 2048);
```

A field a provider sent that the library has no name for is kept on the node it came from, and
written back when that node goes out again. What travels through is never translated — see
[Design and trade-offs](design.md).

## The context

`ChatContext` ties a call to a conversation: a session id, the current turn, the request and
response of the exchange in progress, and a map of attributes the library never reads or sends.

You rarely touch it. The library fills in everything except the attributes; a tool that needs the
conversation receives it as an argument. The attributes are an in-process lane for your own data,
and nothing in them reaches the wire.
