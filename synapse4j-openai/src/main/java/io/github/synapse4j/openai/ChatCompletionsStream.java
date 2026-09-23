package io.github.synapse4j.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import io.github.synapse4j.chat.DefaultChatStream;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.http.SseEvent;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;

/**
 * The chat-completions stream: one streamed exchange, pulled frame by frame, that assembles the
 * answer as it is consumed.
 *
 * <p>
 * One SSE frame becomes one {@link ChatStreamEvent}, in arrival order, and no frame is dropped: a
 * frame that carries nothing this module models — the usage-only frame that ends a streamed answer,
 * say — is still handed out, because whether it is worth an event is the application's decision.
 * The event type is the payload's own {@code object} member, so a kind of chunk this module has
 * never heard of reaches the caller under the name the provider gave it. Each payload's document is
 * walked by {@link ChatCompletionsReader}; this class owns the frames around it — the reader opened
 * over each payload's bytes, and the sentinel frame that ends the answer.
 *
 * <p>
 * The fold is what makes a streamed answer the same answer a blocking call returns: it sums the
 * fragments the way {@link ChatCompletionsReader} reads them, so a turn that arrived as twenty
 * chunks ends up as the one message, and the one tool call, a single response would have carried.
 *
 * <p>
 * One instance per exchange, built by {@link OpenAiChatClient} while the response is open; closing
 * it — or running out of events — releases the connection behind it.
 */
class ChatCompletionsStream extends DefaultChatStream {

    /**
     * The member that says which entry of a chunk's {@code tool_calls} array a fragment belongs to.
     * This module does not model it, so it stays in the part's extras — and is read back from there
     * when a fragment has to be matched to its call.
     */
    private static final String TOOL_CALL_POSITION = "index";

    /**
     * A stream over the given frames.
     *
     * @param codec       the application's codec, for opening a reader over each frame's payload
     * @param sse         the frames of the answer, in arrival order; the caller owns the body
     * @param closeAction what releasing the stream does — typically closing the HTTP response
     *                        behind it; never {@code null}
     */
    ChatCompletionsStream(JsonCodec codec, SseEventStream sse, AutoCloseable closeAction) {
        super(events(codec, sse), ChatCompletionsStream::aggregate, closeAction);
    }

    /**
     * The events of one streamed answer, one per frame of the given stream.
     *
     * <p>
     * Pulling is what reads the body: this iterator asks the frames for their next event only when
     * one is asked of it, so a caller that stops pulling stops the provider. The frame that ends the
     * answer is handed out like any other, and the iterator ends after it.
     *
     * @param codec the codec, for opening a reader over each frame's payload
     * @param sse   the frames, in arrival order; the response behind them is released by the
     *                  stream's close action
     * @return the events; never {@code null}
     */
    private static Iterator<ChatStreamEvent> events(JsonCodec codec, SseEventStream sse) {
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
                pending = toEvent(codec, sse.next());
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

    /** Maps one frame to its event. */
    private static ChatStreamEvent toEvent(JsonCodec codec, SseEvent frame) {
        if (OpenAiEventTypes.DONE.equals(frame.getData())) {
            ChatStreamEvent done = new ChatStreamEvent();
            done.setEventType(OpenAiEventTypes.DONE);
            return done;
        }
        byte[] payload = frame.getData().getBytes(StandardCharsets.UTF_8);
        try (JsonReader reader = codec.reader(new ByteArrayInputStream(payload))) {
            return ChatCompletionsReader.readEvent(reader);
        }
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
            ChatCompletionsReader.extras(message).putAll(deltaExtras);
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
            ChatCompletionsReader.extras(call).putAll(fragmentExtras);
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
