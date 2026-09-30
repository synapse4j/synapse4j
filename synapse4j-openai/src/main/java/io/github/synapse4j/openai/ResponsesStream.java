package io.github.synapse4j.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.synapse4j.chat.DefaultChatStream;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.Usage;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.SseEvent;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import org.jspecify.annotations.Nullable;

/**
 * The Responses stream: one streamed exchange, pulled frame by frame, that assembles the answer as
 * it is consumed.
 *
 * <p>
 * One SSE frame becomes one {@link ChatStreamEvent}, in arrival order, and no frame is dropped: a
 * frame that carries nothing this module models is still handed out, because whether it is worth an
 * event is the application's decision. The event type is the frame's own {@code event} name, so a
 * kind of event this module has never heard of reaches the caller under the name the provider gave
 * it. Each payload is walked by {@link ResponsesReader}; this class owns the frames around it — the
 * reader opened over each payload's bytes, and the end of the body.
 *
 * <p>
 * The fold is what makes a streamed answer the same answer a blocking call returns: the fragments
 * are accumulated the way the reader reads a single response, and the frames that carry the whole
 * response replace what the fragments built with the complete turn. So a turn that arrived as twenty
 * text fragments ends up as the one message a single response would have carried.
 *
 * <p>
 * One instance per exchange, built by {@link OpenAiResponsesChatClient} while the response is open;
 * closing it — or running out of events — releases the connection behind it.
 */
class ResponsesStream extends DefaultChatStream {

    /**
     * A stream over the given frames.
     *
     * @param codec         the application's codec, for opening a reader over each frame's payload
     * @param sse           the frames of the answer, in arrival order; the caller owns the body
     * @param eventPipeline the client's event customizer chain, run on each event between the
     *                          frames and the fold
     * @param closeAction   what releasing the stream does — typically closing the HTTP response
     *                          behind it; never {@code null}
     */
    ResponsesStream(JsonCodec codec, SseEventStream sse, Consumer<ChatStreamEvent> eventPipeline,
            AutoCloseable closeAction) {
        super(events(codec, sse, new ResponsesReader()), eventPipeline, ResponsesStream::aggregate, closeAction);
    }

    /**
     * The events of one streamed answer, one per frame of the given stream.
     *
     * <p>
     * Pulling is what reads the body: this iterator asks the frames for their next event only when
     * one is asked of it, so a caller that stops pulling stops the provider. The answer ends with
     * the terminal frame this protocol marks it with, not with the body: a connection that drops
     * before that frame is an answer cut short, and this iterator fails rather than handing back
     * what is left of one.
     *
     * @param codec       the codec, for opening a reader over each frame's payload
     * @param sse         the frames, in arrival order; the response behind them is released by the
     *                        stream's close action
     * @param eventReader the reader each payload is walked by
     * @return the events; never {@code null}
     */
    private static Iterator<ChatStreamEvent> events(JsonCodec codec, SseEventStream sse,
            ResponsesReader eventReader) {
        return new Iterator<ChatStreamEvent>() {

            private @Nullable ChatStreamEvent pending;

            private boolean ended;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                if (ended) {
                    return false;
                }
                if (!sse.hasNext()) {
                    // This protocol ends every answer with the frame that carries the finished
                    // response: a body that just stops is an answer cut short, not an answer.
                    // What was consumed before the cut stays folded into the aggregated response
                    // the caller already holds.
                    throw new SynapseException("OpenAI Responses stream ended without "
                            + OpenAiResponsesEventTypes.COMPLETED + ": the answer was cut short");
                }
                pending = toEvent(codec, sse.next(), eventReader);
                // The frame that finishes the answer is the last one there is: a provider that
                // sent something after it would be contradicting itself, and nothing here waits
                // for it. A failure frame never reaches here — the reader raises it.
                ended = finishesAnswer(pending.getEventType());
                return true;
            }

            @Override
            public ChatStreamEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("the event stream is over");
                }
                ChatStreamEvent event = Objects.requireNonNull(pending,
                        "hasNext answered true, so an event is pending");
                pending = null;
                return event;
            }
        };
    }

    /** Maps one frame to its event. */
    private static ChatStreamEvent toEvent(JsonCodec codec, SseEvent frame, ResponsesReader eventReader) {
        byte[] payload = frame.getData().getBytes(StandardCharsets.UTF_8);
        try (JsonReader reader = codec.reader(new ByteArrayInputStream(payload))) {
            return eventReader.readEvent(reader, frame.getEvent());
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
            // The answer owns its counts: a frame's usage is a snapshot an application may keep,
            // and the answer must not be the same object under it.
            response.setUsage(copyOf(event.getUsage()));
        }
        // The event's own unmodelled fields belong to the answer the way they belong to a blocking
        // response — folded in as they arrive, the last frame winning, which for the fields that
        // stay constant across a stream is the value the single response carries. The frames that
        // carry a fragment of a part are the exception: their payload is the stream's own bookkeeping
        // — position in the stream, the fragment text — and a blocking response has no frames to
        // carry it. What such a frame says about the answer itself arrives on the frame that closes
        // it, which carries the whole response.
        if (!isFragment(event.getEventType())) {
            response.getExtras().putAll(event.getExtras());
        }
        ChatMessage delta = event.getDelta();
        if (delta == null) {
            return;
        }
        if (finishesAnswer(event.getEventType())) {
            // The event carries the complete turn, so it takes the place of what the fragments built
            // rather than being appended to it. The frames that open the answer also carry the whole
            // response, but an empty one — appending those is harmless, replacing with them would
            // wipe what the fragments already built.
            // The complete turn is the answer's own, copied for the same reason a fragment is: a
            // frame an application kept must not be the object the answer is built on.
            response.setMessage(copyOf(delta));
            return;
        }
        ChatMessage message = response.getMessage();
        if (message.getRole() == null && delta.getRole() != null) {
            // The role is named once, on the frame that opens the turn; the rest of the answer has
            // nothing to say about it.
            message.setRole(delta.getRole());
        }
        // A field the provider put on a fragment is a field of the answer's message, so it travels
        // with it rather than staying behind on the event that happened to carry it.
        ProviderExtras deltaExtras = delta.getExtras();
        if (deltaExtras != null) {
            message.getOrCreateExtras().putAll(deltaExtras);
        }
        for (ContentPart part : delta.getParts()) {
            if (part instanceof TextPart text) {
                appendText(message, text);
            } else if (part instanceof ToolCallPart call) {
                mergeToolCall(message, call);
            } else if (part instanceof ReasoningPart reasoning) {
                appendReasoning(message, reasoning);
            }
        }
    }

    /**
     * Whether an event closes the answer: the frames that carry the finished response. Such a
     * frame's delta is the complete turn, so it replaces what the fragments built — and the body
     * ending before one of them is an answer cut short rather than an answer.
     */
    private static boolean finishesAnswer(@Nullable String eventType) {
        return OpenAiResponsesEventTypes.COMPLETED.equals(eventType)
                || OpenAiResponsesEventTypes.INCOMPLETE.equals(eventType);
    }

    /**
     * Whether an event carries a fragment — a piece of a part, or the announcement of an item —
     * rather than a response of its own. Such a frame's payload is the stream's bookkeeping, kept
     * on the event for the application and folded out of the answer, which a blocking call spells
     * without ever seeing a frame.
     */
    private static boolean isFragment(@Nullable String eventType) {
        return OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA.equals(eventType)
                || OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED.equals(eventType)
                || OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA.equals(eventType)
                || OpenAiResponsesEventTypes.REASONING_SUMMARY_TEXT_DELTA.equals(eventType);
    }

    /** Appends a fragment to the turn's text, which is one part however many frames it took. */
    private static void appendText(ChatMessage message, TextPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof TextPart text) {
            text.setText(join(text.getText(), fragment.getText()));
            return;
        }
        parts.add(copyOf(fragment));
    }

    /**
     * Appends a fragment to the turn's reasoning, which is one part however many frames it took. The
     * fold is what makes a streamed answer carry the same reasoning a blocking call returns as one
     * member — and, before it, what stops a multi-frame answer from keeping only the last fragment.
     */
    private static void appendReasoning(ChatMessage message, ReasoningPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ReasoningPart reasoning) {
            reasoning.setText(join(reasoning.getText(), fragment.getText()));
            ProviderExtras fragmentExtras = fragment.getExtras();
            if (fragmentExtras != null) {
                reasoning.getOrCreateExtras().putAll(fragmentExtras);
            }
            return;
        }
        parts.add(copyOf(fragment));
    }

    /**
     * Merges a fragment into the call it belongs to. A call is announced by one frame naming it and
     * its arguments are then spelled by as many frames as they take, and what arrives is one part
     * carrying the whole of what the provider said about that call.
     */
    private static void mergeToolCall(ChatMessage message, ToolCallPart fragment) {
        ToolCallPart call = toolCallFor(message, fragment);
        if (call == null) {
            message.getParts().add(copyOf(fragment));
            return;
        }
        if (call.getName() == null) {
            call.setName(fragment.getName());
        }
        call.setArgumentsJson(join(call.getArgumentsJson(), fragment.getArgumentsJson()));
        ProviderExtras fragmentExtras = fragment.getExtras();
        if (fragmentExtras != null) {
            call.getOrCreateExtras().putAll(fragmentExtras);
        }
    }

    /**
     * A part the answer owns, so the fold never mutates a part an event handed out: the answer grows
     * by merging later fragments into the part it took, and that part has to be the answer's own, or
     * an application that kept the event would watch its text change under it.
     */
    private static ContentPart copyOf(ContentPart part) {
        ContentPart copy;
        if (part instanceof TextPart text) {
            copy = new TextPart(text.getText());
        } else if (part instanceof ReasoningPart reasoning) {
            copy = new ReasoningPart(reasoning.getText());
        } else if (part instanceof ToolCallPart call) {
            copy = new ToolCallPart(call.getCallId(), call.getName(), call.getArgumentsJson());
        } else {
            return part;
        }
        if (part.getExtras() != null) {
            copy.getOrCreateExtras().putAll(part.getExtras());
        }
        return copy;
    }

    /** The turn the answer owns, copied so it never shares a part a frame handed out. */
    private static ChatMessage copyOf(ChatMessage message) {
        ChatMessage copy = new ChatMessage();
        copy.setRole(message.getRole());
        copy.setId(message.getId());
        for (ContentPart part : message.getParts()) {
            copy.getParts().add(copyOf(part));
        }
        if (message.getExtras() != null) {
            copy.getOrCreateExtras().putAll(message.getExtras());
        }
        return copy;
    }

    /**
     * An independent copy of a frame's counts, so the answer owns the usage it carries and a frame
     * an application kept does not change as later frames report more.
     */
    private static Usage copyOf(Usage usage) {
        Usage copy = new Usage();
        copy.setInputTokens(usage.getInputTokens());
        copy.setOutputTokens(usage.getOutputTokens());
        copy.setCachedInputTokens(usage.getCachedInputTokens());
        copy.getExtras().putAll(usage.getExtras());
        return copy;
    }

    /** The call a fragment continues, or {@code null} when it opens a new one. */
    private static @Nullable ToolCallPart toolCallFor(ChatMessage message, ToolCallPart fragment) {
        List<ContentPart> parts = message.getParts();
        // The frame that announces a call and the frames that spell its arguments carry the item they
        // belong to, and nothing else does: the shared model has no field for it, so it is read back
        // out of the extras the fragment kept it in.
        Object itemId = extra(fragment, ResponsesReader.ITEM_ID);
        if (itemId != null) {
            for (ContentPart part : parts) {
                if (part instanceof ToolCallPart call && itemId.equals(extra(call, ResponsesReader.ITEM_ID))) {
                    return call;
                }
            }
            return null;
        }
        if (fragment.getCallId() != null) {
            for (ContentPart part : parts) {
                if (part instanceof ToolCallPart call && fragment.getCallId().equals(call.getCallId())) {
                    return call;
                }
            }
            return null;
        }
        // Nothing identifies the fragment, so it continues the call that was opened last.
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ToolCallPart open) {
            return open;
        }
        return null;
    }

    /** One member a part kept in its extras, or {@code null} when it carries no bag or not that one. */
    private static @Nullable Object extra(ContentPart part, String name) {
        ProviderExtras extras = part.getExtras();
        return extras != null ? extras.get(name) : null;
    }

    /** The text as it arrives: a fragment is a piece of the value, not the value. */
    private static @Nullable String join(@Nullable String current, @Nullable String fragment) {
        if (fragment == null) {
            return current;
        }
        return current == null ? fragment : current + fragment;
    }

}
