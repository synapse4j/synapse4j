package io.github.synapse4j.anthropic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
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
 * The fold is what turns the frames back into a turn. This protocol brackets every block with a
 * start and a stop frame, and the fold uses that grammar rather than guessing: the frame that opens
 * a block births the block's part outright and registers it under
 * the block's index, and every delta joins the part its own index names — so one block is one part,
 * however many frames it took, and two adjacent blocks never merge into one. Tool input arrives as
 * fragments of JSON text
 * and is concatenated as it comes, which is why a call's arguments are whole by the time the block
 * closes; a call whose input never streamed means the empty object, the same answer a blocking call
 * gives it. The input of a block the shared model has no part for — a server tool's call — is
 * parsed into the block itself when it closes, so both ways of asking read the same document. The
 * counts are merged member by member rather than replaced, because this protocol
 * reports the input side when the answer opens and the output side as it finishes — replacing would
 * lose the first half. The block bracket's {@code index} stays on the event that carried it and is
 * left off the answer, whose document has no such member.
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
        super(events(codec, sse, new MessagesReader(codec)), eventPipeline, new Fold(codec),
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

    /**
     * The fold: one instance per stream, assembling the answer as events are consumed. It tracks
     * which part each block's bracket index named, because a delta belongs to the block its index
     * says — never to whichever part happened to be added last, which is how one block's input used
     * to leak into its neighbour's. The state lives exactly as long as the stream that owns it and
     * is read and written on the thread consuming the iterator.
     */
    private static final class Fold implements BiConsumer<ChatResponse, ChatStreamEvent> {

        private final JsonCodec codec;

        /** The part each open block opened, by the block's bracket index. */
        private final Map<Integer, ContentPart> openBlocks = new HashMap<>();

        /** The input spelled so far for a block that has no part of its own, by its index. */
        private final Map<Integer, StringBuilder> unmodeledInput = new HashMap<>();

        private Fold(JsonCodec codec) {
            this.codec = codec;
        }

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
                mergeUsage(response, event.getUsage());
            }
            foldExtras(response.getExtras(), event.getExtras());
            String eventType = event.getEventType();
            if (AnthropicEventTypes.CONTENT_BLOCK_STOP.equals(eventType)) {
                closeBlock(event);
            }
            if (AnthropicEventTypes.MESSAGE_STOP.equals(eventType)) {
                // Every block has closed by now — or should have: any input whose stop frame never
                // arrived still lands in its block rather than being dropped, and a call whose input
                // never streamed means the empty object, the same answer a blocking call gives it.
                writeUnmodeledInputs();
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
            Integer index = indexOf(event);
            boolean opensBlock = AnthropicEventTypes.CONTENT_BLOCK_START.equals(eventType);
            for (ContentPart part : delta.getParts()) {
                if (opensBlock) {
                    // The frame that opens a block is where the block becomes a part: one block is one
                    // part, however many deltas follow, and a later block never merges into an
                    // earlier one even when the two are of the same kind. The part is the answer's own,
                    // copied from the frame's, so a frame an application kept does not grow under it.
                    ContentPart owned = copyOf(part);
                    message.getParts().add(owned);
                    if (index != null) {
                        openBlocks.put(index, owned);
                    }
                } else {
                    mergeFragment(message, part, index);
                }
            }
        }

        /** Joins one fragment to the block its index names, or places it if that block is unknown. */
        private void mergeFragment(ChatMessage message, ContentPart fragment, @Nullable Integer index) {
            ContentPart target = index == null ? null : openBlocks.get(index);
            if (target instanceof RawContentBlock) {
                // The block keeps itself whole; only its input still streams, as fragments of JSON
                // text that are parsed into the block when it closes — where a blocking walk
                // captured the input whole with the block itself.
                if (index != null && fragment instanceof ToolCallPart call
                        && call.getArgumentsJson() != null) {
                    unmodeledInput.computeIfAbsent(index, ignored -> new StringBuilder())
                            .append(call.getArgumentsJson());
                }
                return;
            }
            if (target != null) {
                mergeInto(target, fragment, message);
                return;
            }
            if (fragment instanceof ToolCallPart) {
                // A fragment with no block to belong to has nowhere trustworthy to go, and inventing
                // a part from it would hand the model a call that was never made.
                return;
            }
            // No opening frame was seen for this index — the fragment opens its own part, and the
            // frames that follow find it here. The part is the answer's own, as above.
            ContentPart owned = copyOf(fragment);
            message.getParts().add(owned);
            if (index != null) {
                openBlocks.put(index, owned);
            }
        }

        /**
         * A part the answer owns, so the fold never mutates a part a frame handed out: the answer
         * grows by merging later fragments into the part it took, and that part has to be the
         * answer's own, or an application that kept the event would watch its text change under it.
         */
        private static ContentPart copyOf(ContentPart part) {
            ContentPart copy;
            if (part instanceof TextPart text) {
                copy = new TextPart(text.getText());
            } else if (part instanceof ReasoningPart reasoning) {
                copy = new ReasoningPart(reasoning.getText());
            } else if (part instanceof ToolCallPart call) {
                copy = new ToolCallPart(call.getCallId(), call.getName(), call.getArgumentsJson());
            } else if (part instanceof RawContentBlock block) {
                copy = new RawContentBlock(block.getMembers());
            } else {
                return part;
            }
            if (part.getExtras() != null) {
                copy.getOrCreateExtras().putAll(part.getExtras());
            }
            return copy;
        }

        /** Closes the block its index names: the part is done, and the input it spelled is written. */
        private void closeBlock(ChatStreamEvent event) {
            Integer index = indexOf(event);
            if (index == null) {
                return;
            }
            ContentPart target = openBlocks.remove(index);
            StringBuilder input = unmodeledInput.remove(index);
            if (input != null && target instanceof RawContentBlock block) {
                writeInput(block, input);
            }
        }

        /** Writes every input still held — for a block whose stop frame never arrived. */
        private void writeUnmodeledInputs() {
            unmodeledInput.forEach((index, input) -> {
                ContentPart target = openBlocks.get(index);
                if (target instanceof RawContentBlock block) {
                    writeInput(block, input);
                }
            });
            unmodeledInput.clear();
        }

        /**
         * Parses the input an unmodeled block spelled and sets it into the block itself, where a
         * blocking walk captured the same input whole.
         */
        private void writeInput(RawContentBlock block, StringBuilder input) {
            if (input.isEmpty()) {
                return;
            }
            Object parsed;
            try {
                parsed = codec.decode(input.toString(), Object.class);
            } catch (RuntimeException failure) {
                // A block whose input never finished arriving leaves truncated JSON. The failure is
                // the library's to report, not the codec's own exception type escaping from a fold:
                // this module's pom carries no JSON library for a caller to catch it by.
                throw new SynapseException("streamed block input is not valid JSON", failure);
            }
            if (parsed != null) {
                block.getMembers().put("input", parsed);
            }
        }

        /** The bracket a frame carries for the block it speaks for, or {@code null} when it names none. */
        private static @Nullable Integer indexOf(ChatStreamEvent event) {
            Object index = event.getExtras().get("index");
            return index instanceof Integer block ? block : null;
        }

        /**
         * Merges a fragment into the part its block opened. The kinds are the block's own — a delta
         * never changes what its block is — and a fragment whose kind disagrees with the part it
         * names is kept as a part of its own rather than forced into a shape it does not fit.
         */
        private static void mergeInto(ContentPart target, ContentPart fragment, ChatMessage message) {
            if (target instanceof TextPart text && fragment instanceof TextPart piece) {
                text.setText(join(text.getText(), piece.getText()));
            } else if (target instanceof ToolCallPart call && fragment instanceof ToolCallPart piece) {
                if (call.getName() == null) {
                    call.setName(piece.getName());
                }
                call.setArgumentsJson(join(call.getArgumentsJson(), piece.getArgumentsJson()));
                ProviderExtras fragmentExtras = piece.getExtras();
                if (fragmentExtras != null) {
                    call.getOrCreateExtras().putAll(fragmentExtras);
                }
            } else if (target instanceof ReasoningPart reasoning && fragment instanceof ReasoningPart piece) {
                reasoning.setText(join(reasoning.getText(), piece.getText()));
                ProviderExtras fragmentExtras = piece.getExtras();
                if (fragmentExtras != null) {
                    reasoning.getOrCreateExtras().putAll(fragmentExtras);
                }
            } else {
                message.getParts().add(copyOf(fragment));
            }
        }
    }

    /**
     * The event's own extras onto the answer, minus what belongs to the event alone: {@code index}
     * says which block a frame arrived for, and {@code delta} is a frame's own fragment — a kind
     * this module did not model, kept on the event for the application rather than promoted to a
     * member of the answer a blocking call could never produce. The answer must not depend on which
     * way it was asked for.
     */
    private static void foldExtras(ProviderExtras responseExtras, ProviderExtras eventExtras) {
        for (Map.Entry<String, Object> member : eventExtras.rawMap().entrySet()) {
            if (!"index".equals(member.getKey()) && !"delta".equals(member.getKey())) {
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
            // The answer owns its counts: a frame's usage is a snapshot an application may keep, so
            // later frames merge into this copy rather than into the frame's own object.
            response.setUsage(copyOf(update));
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

    /** The arguments every assembled call carries at the end: nothing said spells the empty object. */
    private static void normalizeEmptyInputs(ChatMessage message) {
        for (ContentPart part : message.getParts()) {
            if (part instanceof ToolCallPart call
                    && (call.getArgumentsJson() == null || call.getArgumentsJson().isBlank())) {
                call.setArgumentsJson("{}");
            }
        }
    }

    /** The arguments as they arrive: a fragment is a piece of the JSON text, not a value. */
    private static @Nullable String join(@Nullable String current, @Nullable String fragment) {
        if (fragment == null) {
            return current;
        }
        return current == null ? fragment : current + fragment;
    }

}
