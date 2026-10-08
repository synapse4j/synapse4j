package io.github.synapse4j.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

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
 * The fold is what turns the frames back into a turn: the fragments are accumulated the way the
 * reader reads a single response, and the frames that carry the whole response replace what the
 * fragments built with the complete turn. So a turn that arrived as twenty text fragments ends up as
 * the one message a single response would have carried. Because a message is a value, the fold keeps
 * the turn's pieces and rebuilds the message whenever one of them changes.
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
        super(events(codec, sse, new ResponsesReader()), eventPipeline, new Aggregation(), closeAction);
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

    /**
     * The turn being assembled across the frames of one stream. The message is a value, so the fold
     * holds the turn's pieces and rebuilds the message whenever one of them changes.
     */
    private static final class Aggregation implements BiConsumer<ChatResponse, ChatStreamEvent> {

        private @Nullable String role;

        private final List<ContentPart> parts = new ArrayList<>();

        private final ProviderExtras extras = new ProviderExtras();

        @Override
        public void accept(ChatResponse response, ChatStreamEvent event) {
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
            // The frames that carry the whole response are the only ones whose extras are the
            // answer's: those hold the response's own unmodelled members. Any other frame's payload
            // is stream bookkeeping — where in the stream it sat, the item it spoke for, the
            // fragment's text — which is not a field of any answer. Naming the whole-response frames
            // is also what keeps a kind of frame this module has never heard of from leaking its
            // payload into the answer.
            if (carriesWholeResponse(event.getEventType())) {
                response.getExtras().putAll(event.getExtras());
            }
            ChatMessage delta = event.getDelta();
            if (delta == null) {
                return;
            }
            if (finishesAnswer(event.getEventType())) {
                // The event carries the complete turn, so it takes the place of what the fragments
                // built rather than being appended to it. The frames that open the answer also carry
                // the whole response, but an empty one — appending those is harmless, replacing with
                // them would wipe what the fragments already built.
                response.setMessage(delta);
                return;
            }
            boolean changed = false;
            if (role == null && delta.getRole() != null) {
                // The role is named once, on the frame that opens the turn; the rest of the answer
                // has nothing to say about it.
                role = delta.getRole();
                changed = true;
            }
            // A field the provider put on a fragment is a field of the answer's message, so it
            // travels with it rather than staying behind on the event that happened to carry it.
            ProviderExtras deltaExtras = delta.getExtras();
            if (deltaExtras != null) {
                extras.putAll(deltaExtras);
                changed = true;
            }
            for (ContentPart part : delta.getParts()) {
                if (part instanceof TextPart text) {
                    appendText(text);
                    changed = true;
                } else if (part instanceof ToolCallPart call) {
                    mergeToolCall(call);
                    changed = true;
                } else if (part instanceof ReasoningPart reasoning) {
                    appendReasoning(reasoning);
                    changed = true;
                }
            }
            if (changed) {
                response.setMessage(new ChatMessage(role, null, parts, extras));
            }
        }

        /** Appends a fragment to the turn's text, which is one part however many frames it took. */
        private void appendText(TextPart fragment) {
            if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof TextPart text) {
                parts.set(parts.size() - 1, new TextPart(join(text.getText(), fragment.getText()), text.getExtras()));
                return;
            }
            parts.add(fragment);
        }

        /**
         * Appends a fragment to the turn's reasoning, which is one part however many frames it took —
         * and, before that, what stops a multi-frame answer from keeping only the last fragment.
         */
        private void appendReasoning(ReasoningPart fragment) {
            if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ReasoningPart reasoning) {
                parts.set(parts.size() - 1, new ReasoningPart(join(reasoning.getText(), fragment.getText()),
                        ProviderExtras.merged(reasoning.getExtras(), fragment.getExtras())));
                return;
            }
            parts.add(fragment);
        }

        /**
         * Merges a fragment into the call it belongs to. A call is announced by one frame naming it
         * and its arguments are then spelled by as many frames as they take, and what arrives is one
         * part carrying the whole of what the provider said about that call.
         */
        private void mergeToolCall(ToolCallPart fragment) {
            int index = toolCallIndex(fragment);
            if (index < 0) {
                parts.add(fragment);
                return;
            }
            ToolCallPart call = (ToolCallPart) parts.get(index);
            String callName = call.getName() != null ? call.getName() : fragment.getName();
            parts.set(index, new ToolCallPart(call.getCallId(), callName,
                    join(call.getArgumentsJson(), fragment.getArgumentsJson()),
                    ProviderExtras.merged(call.getExtras(), fragment.getExtras())));
        }

        /**
         * The call a fragment continues, as the position it occupies in the turn, or {@code -1} when
         * it opens a new one.
         */
        private int toolCallIndex(ToolCallPart fragment) {
            // The frame that announces a call and the frames that spell its arguments carry the item
            // they belong to, and nothing else does: the shared model has no field for it, so it is
            // read back out of the extras the fragment kept it in.
            Object itemId = extra(fragment, ResponsesReader.ITEM_ID);
            if (itemId != null) {
                for (int i = 0; i < parts.size(); i++) {
                    if (parts.get(i) instanceof ToolCallPart call
                            && itemId.equals(extra(call, ResponsesReader.ITEM_ID))) {
                        return i;
                    }
                }
                return -1;
            }
            if (fragment.getCallId() != null) {
                for (int i = 0; i < parts.size(); i++) {
                    if (parts.get(i) instanceof ToolCallPart call && fragment.getCallId().equals(call.getCallId())) {
                        return i;
                    }
                }
                return -1;
            }
            // Nothing identifies the fragment, so it continues the call that was opened last.
            if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ToolCallPart) {
                return parts.size() - 1;
            }
            return -1;
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
     * Whether an event carries the whole response, and so the answer's own unmodelled members.
     * Everything else a frame carries is bookkeeping for the stream — a position, an item it speaks
     * for, a fragment — and stays on the event, where a blocking call's answer has no counterpart
     * to fold it into.
     */
    private static boolean carriesWholeResponse(@Nullable String eventType) {
        return OpenAiResponsesEventTypes.CREATED.equals(eventType)
                || OpenAiResponsesEventTypes.IN_PROGRESS.equals(eventType)
                || finishesAnswer(eventType);
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
