# Streaming

**English** | [中文](../zh-CN/streaming.md)

An answer can be consumed as it arrives or waited for whole. The request is the same either way;
what differs is what comes back — the complete answer, or its events one at a time, in the order
the provider sends them.

```java
try (ChatStream stream = client.stream(request)) {
    for (ChatStreamEvent event : stream) {
        if (event.getDelta() != null) {
            for (ContentPart part : event.getDelta().getParts()) {
                if (part instanceof TextPart text) {
                    System.out.print(text.getText());
                }
            }
        }
    }
}
```

## Events

An event is one protocol event, mapped one to one and kept in arrival order. It carries the
protocol's own `eventType` — so you can dispatch on it — plus a normalized view where there is one:
a `delta` message with the parts this event adds, a `finishReason`, the `usage`, and the response
`id` and `model`.

Each provider module declares its own event types as constants:

| Module | Examples |
|---|---|
| `synapse4j-openai` (Completions) | `OpenAiEventTypes.CHUNK`, `OpenAiEventTypes.DONE` |
| `synapse4j-openai` (Responses) | `OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA`, `.COMPLETED` |
| `synapse4j-anthropic` | `AnthropicEventTypes.CONTENT_BLOCK_DELTA`, `.MESSAGE_STOP` |

An event that carries no normalized content — a block start or stop, a lifecycle marker — leaves
`getDelta()` null and keeps its payload in `getExtras()`.

## The assembled answer

The stream folds every event it hands out into an aggregated response, so the same consumption that
drives a UI builds the complete answer:

```java
ChatResponse response = stream.aggregatedResponse();
```

After the loop runs to its end, it holds the turn `chat` would have returned. It never blocks and
never drives consumption — it reports what the iterator has already folded. The fold runs on the
thread consuming the iterator, so read it from that thread too: calling it from another thread while
the stream is being consumed races with the fold, and of the stream's methods only `close()` is safe
from any thread.

## Pulling, and stopping early

Iteration is lazy and blocking: `hasNext()` waits for the next event, which is also what
backpressures the provider. The thread that iterates is the thread events arrive on.

A stream is one pass: the first call to `iterator()` hands back the iterator, and a second call
throws. Closing releases the connection behind the stream, cancelling a response still in flight;
it is idempotent and safe to call from any thread. A stream that runs to its end releases the same
connection by itself, so consuming an answer completely needs no close — a loop that breaks out
early is the one that does, and try-with-resources covers it.

Failures surface where the pull happens: a request the provider refuses fails before anything is
returned, and a connection that drops mid-answer fails while iterating.

## Streaming with tools

`ToolCallingChatClient.stream` splices the rounds' streams into one sequence: the loop advances
inside the pull, executing a round's tool batch and opening the next round before the next event
arrives. The boundary between rounds is the protocol's own; no marker is synthesized.
