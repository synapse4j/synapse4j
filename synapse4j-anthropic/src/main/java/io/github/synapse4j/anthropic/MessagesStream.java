package io.github.synapse4j.anthropic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * The Messages stream: one streamed exchange, pulled frame by frame, that assembles the answer as
 * it is consumed.
 *
 * <p>
 * One SSE frame becomes one {@link ChatStreamEvent}, in arrival order, and no frame is dropped: a
 * frame that carries nothing this module models — a {@code ping}, the bracket that closes a block —
 * is still handed out, because whether it is worth an event is the application's decision. The
 * event type is the frame's own {@code event} name, so a kind of event this module has never heard
 * of reaches the caller under the name the provider gave it. Each payload is walked by
 * {@link MessagesReader}; this class owns the frames around it — the reader opened over each
 * payload's bytes, and the terminal frame that ends the answer.
 *
 * <p>
 * The fold is what makes a streamed answer the same answer a blocking call returns. This protocol
 * brackets every block with a start and a stop frame, and the fold uses that grammar rather than
 * guessing: the frame that opens a block births the block's part outright, and every delta joins
 * the one block that is open — so one block is one part, however many frames it took, and two
 * adjacent blocks never merge into one. Tool input arrives as fragments of JSON text and is
 * concatenated as it comes, which is why a call's arguments are whole by the time the block closes;
 * a call whose input never streamed means the empty object, the same answer a blocking call gives
 * it. The counts are merged member by member rather than replaced, because this protocol reports
 * the input side when the answer opens and the output side as it finishes — replacing would lose
 * the first half. The block bracket's {@code index} stays on the event that carried it and is left
 * off the answer, whose document has no such member.
 *
 * <p>
 * One instance per exchange, built by {@link AnthropicChatClient} while the response is open;
 * closing it — or running out of events — releases the connection behind it.
 */
class MessagesStream extends DefaultChatStream {

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
    MessagesStream(JsonCodec codec, SseEventStream sse, Consumer<ChatStreamEvent> eventPipeline,
            AutoCloseable closeAction) {
        super(events(codec, sse, new MessagesReader(codec)), eventPipeline, MessagesStream::aggregate,
                closeAction);
    }

    /**
     * The events of one streamed answer, one per frame of the given stream.
     *
     * <p>
     * Pulling is what reads the body: this iterator asks the frames for their next event only when
     * one is asked of it, so a caller that stops pulling stops the provider. The frame that ends
     * the answer is handed out like any other; a body that stops without that frame fails the pull
     * instead — half an answer must not pass for one. What was consumed before the cut stays
     * folded into the aggregated response the caller already holds.
     *
     * @param codec the codec, for opening a reader over each frame's payload
     * @param sse   the frames, in arrival order; the response behind them is released by the
     *                  stream's close action
     * @return the events; never {@code null}
     */
    private static Iterator<ChatStreamEvent> events(JsonCodec codec, SseEventStream sse,
            MessagesReader eventReader) {
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
                    // This protocol marks the end of every answer with message_stop: a body that
                    // just stops is an answer cut short, not an answer.
                    throw new SynapseException(
                            "Anthropic stream ended without message_stop: the answer was cut short");
                }
                pending = toEvent(codec, sse.next(), eventReader);
                // The frame that ends the answer is the last one there is: a provider that sent
                // something after it would be contradicting itself, and nothing here waits for it.
                ended = AnthropicEventTypes.MESSAGE_STOP.equals(pending.getEventType());
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
    private static ChatStreamEvent toEvent(JsonCodec codec, SseEvent frame, MessagesReader eventReader) {
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
            mergeUsage(response, event.getUsage());
        }
        foldExtras(response.getExtras(), event.getExtras());
        if (AnthropicEventTypes.MESSAGE_STOP.equals(event.getEventType())) {
            // Every block has closed by now, so a call whose input never streamed — a tool taking
            // no arguments spells nothing — is the empty object, the same answer a blocking call
            // gives it, and the same thing a replay can send back.
            normalizeEmptyInputs(response.getMessage());
        }
        ChatMessage delta = event.getDelta();
        if (delta == null) {
            return;
        }
        ChatMessage message = response.getMessage();
        if (message.getRole() == null && delta.getRole() != null) {
            // The role is named once, on the frame that opens the turn; the rest of the answer has
            // nothing to say about it.
            message.setRole(delta.getRole());
        }
        // A field the provider put on a fragment is a field of the answer's message, so it travels
        // with it rather than staying behind on the frame that happened to carry it.
        ProviderExtras deltaExtras = delta.getExtras();
        if (deltaExtras != null) {
            message.getOrCreateExtras().putAll(deltaExtras);
        }
        boolean opensBlock = AnthropicEventTypes.CONTENT_BLOCK_START.equals(event.getEventType());
        for (ContentPart part : delta.getParts()) {
            if (opensBlock) {
                // The frame that opens a block is where the block becomes a part: one block is one
                // part, however many deltas follow, and a later block never merges into an
                // earlier one even when the two are of the same kind.
                message.getParts().add(part);
            } else if (part instanceof TextPart text) {
                appendText(message, text);
            } else if (part instanceof ToolCallPart call) {
                mergeToolCall(message, call);
            } else if (part instanceof ReasoningPart reasoning) {
                appendReasoning(message, reasoning);
            } else {
                message.getParts().add(part);
            }
        }
    }

    /**
     * The event's own extras onto the answer, minus the block bracket's position: {@code index}
     * says which block a frame arrived for, which belongs to the event and not to the message
     * document a blocking call reads — the answer must not depend on which way it was asked for.
     */
    private static void foldExtras(ProviderExtras responseExtras, ProviderExtras eventExtras) {
        for (Map.Entry<String, Object> member : eventExtras.rawMap().entrySet()) {
            if (!"index".equals(member.getKey())) {
                responseExtras.putRaw(member.getKey(), member.getValue());
            }
        }
    }

    /**
     * Merges a frame's counts into the answer's, member by member. The frame that opens the answer
     * reports what the request consumed; the frames that close it report what generation has cost
     * so far, cumulative. Replacing the whole object would drop the input side the moment the
     * output side arrived, so each count updates only where the frame has one.
     */
    private static void mergeUsage(ChatResponse response, Usage update) {
        Usage current = response.getUsage();
        if (current == null) {
            response.setUsage(update);
            return;
        }
        if (update.getInputTokens() != null) {
            current.setInputTokens(update.getInputTokens());
        }
        if (update.getOutputTokens() != null) {
            current.setOutputTokens(update.getOutputTokens());
        }
        if (update.getCachedInputTokens() != null) {
            current.setCachedInputTokens(update.getCachedInputTokens());
        }
        current.getExtras().putAll(update.getExtras());
    }

    /** The arguments every assembled call carries at the end: nothing said spells the empty object. */
    private static void normalizeEmptyInputs(ChatMessage message) {
        for (ContentPart part : message.getParts()) {
            if (part instanceof ToolCallPart call
                    && (call.getArgumentsJson() == null || call.getArgumentsJson().isBlank())) {
                call.setArgumentsJson("{}");
            }
        }
    }

    /** Appends a fragment to the turn's text, which is one part however many frames it took. */
    private static void appendText(ChatMessage message, TextPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof TextPart text) {
            text.setText(join(text.getText(), fragment.getText()));
            return;
        }
        parts.add(fragment);
    }

    /**
     * Appends a fragment to the turn's reasoning, which is one block however many frames it took.
     * The fold is what makes a streamed answer carry the same reasoning a blocking call returns as
     * one part — signature included, the delta that carried it having kept it in its extras.
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
        parts.add(fragment);
    }

    /**
     * Merges a fragment into the call it belongs to. A call is announced by the frame that opens
     * its block, naming it, and its input is then spelled by as many deltas as it takes — the
     * arguments are pieces of JSON text, so they are concatenated as they come.
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
            call.getOrCreateExtras().putAll(fragmentExtras);
        }
    }

    /** The call a fragment continues, or {@code null} when it opens a new one. */
    private static @Nullable ToolCallPart toolCallFor(ChatMessage message, ToolCallPart fragment) {
        List<ContentPart> parts = message.getParts();
        if (fragment.getCallId() != null) {
            for (ContentPart part : parts) {
                if (part instanceof ToolCallPart call && fragment.getCallId().equals(call.getCallId())) {
                    return call;
                }
            }
            return null;
        }
        // An input fragment carries no id — the protocol names the call once, on the frame that
        // opens its block — so it continues the call that is open, which is the one added last.
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof ToolCallPart open) {
            return open;
        }
        return null;
    }

    /** The arguments as they arrive: a fragment is a piece of the JSON text, not a value. */
    private static @Nullable String join(@Nullable String current, @Nullable String fragment) {
        if (fragment == null) {
            return current;
        }
        return current == null ? fragment : current + fragment;
    }

}
