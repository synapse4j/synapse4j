package io.github.synapse4j.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.SseEvent;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Translates the chat-completions event stream into the shared streaming model, and folds the
 * events back into the answer they spell out.
 *
 * <p>
 * One SSE frame becomes one {@link ChatStreamEvent}, in arrival order, and no frame is dropped: a
 * frame that carries nothing this module models — the usage-only frame that ends a streamed answer,
 * say — is still handed out, because whether it is worth an event is the application's decision
 * rather than this adapter's. The event type is the payload's own {@code object} member, so a kind
 * of chunk this module has never heard of reaches the caller under the name the provider gave it.
 *
 * <p>
 * Each payload is walked token by token through {@link JsonReader}, the way the blocking adapter
 * walks a whole response, and what this module does not model is kept in the extras of the node it
 * came from.
 *
 * <p>
 * The aggregation is what makes a streamed answer the same answer a blocking call returns: it sums
 * the fragments the way the blocking adapter reads them, so a turn that arrived as twenty chunks
 * ends up as the one message, and the one tool call, a single response would have carried.
 *
 * <p>
 * Stateless; holds only the application's codec, for opening a reader over each frame.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ChatCompletionsStreamAdapter {

    /**
     * The member that says which entry of a chunk's {@code tool_calls} array a fragment belongs to.
     * This module does not model it, so it stays in the part's extras — and is read back from there
     * when a fragment has to be matched to its call.
     */
    private static final String TOOL_CALL_POSITION = "index";

    private final JsonCodec codec;

    /**
     * The events of one streamed answer, one per frame of the given reader.
     *
     * <p>
     * Pulling is what reads the body: this iterator asks the frames for their next event only when
     * one is asked of it, so a caller that stops pulling stops the provider. The frame that ends the
     * answer is handed out like any other, and the iterator ends after it.
     *
     * @param sse the frames, in arrival order; the caller owns the reader and its body
     * @return the events; never {@code null}
     */
    Iterator<ChatStreamEvent> events(SseEventStream sse) {
        return new Iterator<ChatStreamEvent>() {

            private ChatStreamEvent pending;

            private boolean ended;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                if (ended || !sse.hasNext()) {
                    return false;
                }
                pending = toEvent(sse.next());
                // The frame that ends the answer is the last one there is: a provider that sent
                // something after it would be contradicting itself, and nothing here waits for it.
                ended = OpenAiEventTypes.DONE.equals(pending.getEventType());
                return true;
            }

            @Override
            public ChatStreamEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("the event stream is over");
                }
                ChatStreamEvent event = pending;
                pending = null;
                return event;
            }
        };
    }

    /**
     * How one consumed event updates the answer being assembled. The result is the same
     * {@link ChatResponse} {@code chat()} returns for the same answer, built from the fragments
     * instead of from one document.
     *
     * @return the fold; never {@code null}
     */
    BiConsumer<ChatResponse, ChatStreamEvent> aggregation() {
        return ChatCompletionsStreamAdapter::aggregate;
    }

    /** Maps one frame to its event. */
    private ChatStreamEvent toEvent(SseEvent frame) {
        if (OpenAiEventTypes.DONE.equals(frame.getData())) {
            ChatStreamEvent done = new ChatStreamEvent();
            done.setEventType(OpenAiEventTypes.DONE);
            return done;
        }
        ChatStreamEvent event = new ChatStreamEvent();
        event.setEventType(OpenAiEventTypes.CHUNK);
        byte[] payload = frame.getData().getBytes(StandardCharsets.UTF_8);
        try (JsonReader reader = codec.reader(new ByteArrayInputStream(payload))) {
            if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
                throw new SynapseException("OpenAI stream event was not a JSON object");
            }
            while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
                String field = reader.name();
                reader.nextToken();
                switch (field) {
                    case "id" -> event.setId(reader.string());
                    case "model" -> event.setModel(reader.string());
                    case "object" -> readEventType(reader, event);
                    case "choices" -> readChoices(reader, event);
                    case "usage" -> event.setUsage(ChatCompletionsAdapter.readUsage(reader));
                    // A failure can arrive as a frame of its own after the answer was accepted, so
                    // it is raised here rather than left for the aggregation to notice.
                    case "error" -> throw streamError(reader.captureValue());
                    default -> event.getExtras().put(field, reader.captureValue());
                }
            }
        }
        return event;
    }

    /**
     * Takes the payload's own name for the event, or leaves the default when it names none. The
     * name is what the application dispatches on, so it is read as written rather than mapped onto
     * a closed set.
     */
    private static void readEventType(JsonReader reader, ChatStreamEvent event) {
        String type = reader.string();
        if (type != null) {
            event.setEventType(type);
        }
    }

    private void readChoices(JsonReader reader, ChatStreamEvent event) {
        if (reader.token() != JsonReader.Token.START_ARRAY) {
            reader.skipValue();
            return;
        }
        // The reader is on the value the "choices" name introduced, so the array's own start token
        // is the current one and the first element arrives with the next call.
        boolean first = true;
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                continue;
            }
            if (first) {
                readChoice(reader, event, 0);
                first = false;
            } else {
                // The shared model carries a single message, so a further choice has nowhere to go.
                reader.skipValue();
            }
        }
    }

    private void readChoice(JsonReader reader, ChatStreamEvent event, int position) {
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "finish_reason" -> readFinishReason(reader, event);
                case "delta" -> readDelta(reader, event);
                // A choice field this module does not model keeps the path it came from, so a
                // provider's addition stays readable even though it belongs to a choice.
                default -> event.getExtras().put(List.of("choices", String.valueOf(position), field),
                        reader.captureValue());
            }
        }
    }

    /** A chunk that is still generating carries no reason, and a null one is not a reason. */
    private static void readFinishReason(JsonReader reader, ChatStreamEvent event) {
        String reason = reader.string();
        if (reason != null) {
            event.setFinishReason(reason);
        }
    }

    private void readDelta(JsonReader reader, ChatStreamEvent event) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        ChatMessage delta = new ChatMessage();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "role" -> delta.setRole(reader.string());
                case "content" -> readDeltaContent(reader, delta);
                case "tool_calls" -> readDeltaToolCalls(reader, delta);
                // Refusal and anything else the provider puts beside the content belongs to the
                // message being built, so it stays on the delta rather than on the chunk.
                default -> extras(delta).put(field, reader.captureValue());
            }
        }
        event.setDelta(delta);
    }

    private static void readDeltaContent(JsonReader reader, ChatMessage delta) {
        if (reader.token() != JsonReader.Token.STRING) {
            reader.skipValue();
            return;
        }
        String fragment = reader.string();
        if (!fragment.isEmpty()) {
            // An empty fragment is the provider announcing a turn it has not started saying yet;
            // it adds nothing, and a part for it would outlive the chunks it came in.
            delta.getParts().add(new TextPart(fragment));
        }
    }

    private void readDeltaToolCalls(JsonReader reader, ChatMessage delta) {
        if (reader.token() != JsonReader.Token.START_ARRAY) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                continue;
            }
            readDeltaToolCall(reader, delta);
        }
    }

    private void readDeltaToolCall(JsonReader reader, ChatMessage delta) {
        ToolCallPart call = new ToolCallPart();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "id" -> call.setCallId(reader.string());
                case "function" -> readDeltaToolCallFunction(reader, call);
                default -> extras(call).put(field, reader.captureValue());
            }
        }
        delta.getParts().add(call);
    }

    private void readDeltaToolCallFunction(JsonReader reader, ToolCallPart call) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "name" -> call.setName(reader.string());
                case "arguments" -> call.setArgumentsJson(reader.string());
                // Kept under the path it came from, the way every other extras entry is spelled.
                default -> extras(call).put(List.of("function", field), reader.captureValue());
            }
        }
    }

    /**
     * The exception for a failure the provider reports inside the stream, where no HTTP status is
     * involved: the response was accepted, and the refusal arrives as a frame of its own. The
     * message keeps the provider's own detail, type and code, the way a refused call's does.
     */
    private static SynapseException streamError(Object error) {
        StringBuilder message = new StringBuilder("OpenAI stream failed");
        if (error instanceof Map<?, ?> detail) {
            appendDetail(message, detail.get("message"), ": ", "");
            appendDetail(message, detail.get("type"), " [", "]");
            appendDetail(message, detail.get("code"), " (", ")");
        } else if (error != null) {
            message.append(": ").append(error);
        }
        return new SynapseException(message.toString());
    }

    private static void appendDetail(StringBuilder message, Object detail, String prefix, String suffix) {
        if (detail != null) {
            message.append(prefix).append(detail).append(suffix);
        }
    }

    /** The bag to record into, created when the node carries none yet. */
    private static ProviderExtras extras(ChatMessage message) {
        ProviderExtras extras = message.getExtras();
        if (extras == null) {
            extras = new ProviderExtras();
            message.setExtras(extras);
        }
        return extras;
    }

    /** The bag to record into, created when the node carries none yet. */
    private static ProviderExtras extras(ContentPart part) {
        ProviderExtras extras = part.getExtras();
        if (extras == null) {
            extras = new ProviderExtras();
            part.setExtras(extras);
        }
        return extras;
    }

    /** Folds one event into the answer being assembled. */
    private static void aggregate(ChatResponse response, ChatStreamEvent event) {
        if (event.getId() != null) {
            response.setId(event.getId());
        }
        if (event.getModel() != null) {
            response.setModel(event.getModel());
        }
        if (event.getFinishReason() != null) {
            response.setFinishReason(event.getFinishReason());
        }
        if (event.getUsage() != null) {
            response.setUsage(event.getUsage());
        }
        ChatMessage delta = event.getDelta();
        if (delta == null) {
            return;
        }
        ChatMessage message = response.getMessage();
        if (message.getRole() == null && delta.getRole() != null) {
            // The role is named once, on the chunk that opens the turn; the rest of the answer has
            // nothing to say about it.
            message.setRole(delta.getRole());
        }
        // A field the provider put on a delta is a field of the answer's message, so it travels
        // with it rather than staying behind on the chunk that happened to carry it.
        ProviderExtras deltaExtras = delta.getExtras();
        if (deltaExtras != null) {
            extras(message).putAll(deltaExtras);
        }
        for (ContentPart part : delta.getParts()) {
            if (part instanceof TextPart text) {
                appendText(message, text);
            } else if (part instanceof ToolCallPart call) {
                mergeToolCall(message, call);
            }
        }
    }

    /** Appends a fragment to the turn's text, which is one part however many chunks it took. */
    private static void appendText(ChatMessage message, TextPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof TextPart text) {
            text.setText(text.getText() + fragment.getText());
            return;
        }
        parts.add(new TextPart(fragment.getText()));
    }

    /**
     * Merges a fragment into the call it belongs to. A call is sent as one chunk naming it and as
     * many chunks as its arguments take to spell, and what arrives is one part carrying the whole
     * of what the provider said about that call.
     */
    private static void mergeToolCall(ChatMessage message, ToolCallPart fragment) {
        ToolCallPart call = toolCallFor(message, fragment);
        if (call == null) {
            message.getParts().add(fragment);
            return;
        }
        if (call.getName() == null) {
            call.setName(fragment.getName());
        }
        call.setArgumentsJson(join(call.getArgumentsJson(), fragment.getArgumentsJson()));
        ProviderExtras fragmentExtras = fragment.getExtras();
        if (fragmentExtras != null) {
            extras(call).putAll(fragmentExtras);
        }
    }

    /** The call a fragment continues, or {@code null} when it opens a new one. */
    private static ToolCallPart toolCallFor(ChatMessage message, ToolCallPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (fragment.getCallId() != null) {
            for (ContentPart part : parts) {
                if (part instanceof ToolCallPart call && fragment.getCallId().equals(call.getCallId())) {
                    return call;
                }
            }
            return null;
        }
        // A fragment without an id carries only the position it occupies in the chunk's array, which
        // is what tells two calls being spelled at the same time apart. It is read back out of the
        // extras the fragment kept it in: the shared model has no field for it, and the protocol's
        // own way of saying which call is meant is worth more than the order the fragments arrive in.
        ProviderExtras fragmentExtras = fragment.getExtras();
        Object position = fragmentExtras != null ? fragmentExtras.get(TOOL_CALL_POSITION) : null;
        if (position != null) {
            for (ContentPart part : parts) {
                if (part instanceof ToolCallPart call && call.getExtras() != null
                        && position.equals(call.getExtras().get(TOOL_CALL_POSITION))) {
                    return call;
                }
            }
        }
        // Nothing identifies the fragment, so it continues the call that was opened last.
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ToolCallPart open) {
            return open;
        }
        return null;
    }

    /** The arguments as they arrive: a fragment is a piece of the JSON text, not a value. */
    private static String join(String current, String fragment) {
        if (fragment == null) {
            return current;
        }
        return current == null ? fragment : current + fragment;
    }

}
