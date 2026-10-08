package io.github.synapse4j.anthropic;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * closes; a call whose input never streamed means the empty object, which is what this protocol
 * sends for a tool that takes no arguments. The input of a block the shared model has no part for —
 * a server tool's call — is parsed into the block itself when it closes, as the object the protocol
 * expects when that block is sent back. The
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
     * The fold: one instance per stream, assembling the answer as events are consumed. It holds the
     * answer's parts, its role and its message-level fields as it goes, and rebuilds the aggregated
     * message whenever one of them changes — a part is a value, so a fragment joining a block makes
     * a new part in the old one's place rather than changing the one a frame handed out. It tracks
     * which part each block's bracket index named, because a delta belongs to the block its index
     * says — never to whichever part happened to be added last, which is how one block's input used
     * to leak into its neighbour's. The state lives exactly as long as the stream that owns it and
     * is read and written on the thread consuming the iterator.
     */
    private static final class Fold implements BiConsumer<ChatResponse, ChatStreamEvent> {

        private final JsonCodec codec;

        /** The answer's parts, in the order they arrived. */
        private final List<ContentPart> parts = new ArrayList<>();

        /** The role the frame that opened the turn named, or {@code null} until one does. */
        private @Nullable String role;

        /** Provider-specific fields lifted off fragments onto the answer's message. */
        private final ProviderExtras messageExtras = new ProviderExtras();

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
            boolean messageChanged = false;
            if (AnthropicEventTypes.CONTENT_BLOCK_STOP.equals(eventType)) {
                messageChanged = closeBlock(event);
            }
            if (AnthropicEventTypes.MESSAGE_STOP.equals(eventType)) {
                // Every block has closed by now — or should have: any input whose stop frame never
                // arrived still lands in its block rather than being dropped, and a call whose input
                // never streamed means the empty object, which is what the protocol sends for a tool
                // that takes no arguments.
                messageChanged = writeUnmodeledInputs() || messageChanged;
                messageChanged = normalizeEmptyInputs() || messageChanged;
            }
            ChatMessage delta = event.getDelta();
            if (delta != null) {
                if (role == null && delta.getRole() != null) {
                    // The role is named once, on the frame that opens the turn; the rest of the answer
                    // has nothing to say about it.
                    role = delta.getRole();
                }
                // A field the provider put on a fragment is a field of the answer's message, so it
                // travels with it rather than staying behind on the frame that happened to carry it.
                ProviderExtras deltaExtras = delta.getExtras();
                if (deltaExtras != null) {
                    messageExtras.putAll(deltaExtras);
                }
                Integer index = indexOf(event);
                boolean opensBlock = AnthropicEventTypes.CONTENT_BLOCK_START.equals(eventType);
                for (ContentPart part : delta.getParts()) {
                    if (opensBlock) {
                        // The frame that opens a block is where the block becomes a part: one block is
                        // one part, however many deltas follow, and a later block never merges into an
                        // earlier one even when the two are of the same kind.
                        parts.add(part);
                        if (index != null) {
                            openBlocks.put(index, part);
                        }
                    } else {
                        mergeFragment(part, index);
                    }
                }
                messageChanged = true;
            }
            if (messageChanged) {
                publish(response);
            }
        }

        /**
         * The aggregated message as the value it now is — the parts, role and fields the fold holds.
         * An empty bag is no bag, so the constructor leaves an unconfigured message without one.
         */
        private void publish(ChatResponse response) {
            response.setMessage(new ChatMessage(role, null, parts, messageExtras));
        }

        /** Joins one fragment to the block its index names, or places it if that block is unknown. */
        private void mergeFragment(ContentPart fragment, @Nullable Integer index) {
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
                mergeInto(target, fragment);
                return;
            }
            if (fragment instanceof ToolCallPart) {
                // A fragment with no block to belong to has nowhere trustworthy to go, and inventing
                // a part from it would hand the model a call that was never made.
                return;
            }
            // No opening frame was seen for this index — the fragment opens its own part, and the
            // frames that follow find it here.
            parts.add(fragment);
            if (index != null) {
                openBlocks.put(index, fragment);
            }
        }

        /**
         * Closes the block its index names: the part is done, and the input it spelled is written.
         *
         * @return whether the block's part was rebuilt to carry the input it spelled
         */
        private boolean closeBlock(ChatStreamEvent event) {
            Integer index = indexOf(event);
            if (index == null) {
                return false;
            }
            ContentPart target = openBlocks.remove(index);
            StringBuilder input = unmodeledInput.remove(index);
            if (input != null && target instanceof RawContentBlock block) {
                return writeInput(block, input);
            }
            return false;
        }

        /**
         * Writes every input still held — for a block whose stop frame never arrived.
         *
         * @return whether any block's part was rebuilt to carry the input it spelled
         */
        private boolean writeUnmodeledInputs() {
            boolean written = false;
            for (Map.Entry<Integer, StringBuilder> entry : unmodeledInput.entrySet()) {
                ContentPart target = openBlocks.get(entry.getKey());
                if (target instanceof RawContentBlock block && writeInput(block, entry.getValue())) {
                    written = true;
                }
            }
            unmodeledInput.clear();
            return written;
        }

        /**
         * Gives every assembled call the arguments it carries at the end: nothing said spells the
         * empty object.
         *
         * @return whether any call's part was rebuilt to carry the empty object
         */
        private boolean normalizeEmptyInputs() {
            boolean normalized = false;
            for (ContentPart part : List.copyOf(parts)) {
                if (part instanceof ToolCallPart call
                        && (call.getArgumentsJson() == null || call.getArgumentsJson().isBlank())
                        && replace(part, new ToolCallPart(call.getCallId(), call.getName(), "{}",
                                call.getExtras()))) {
                    normalized = true;
                }
            }
            return normalized;
        }

        /**
         * Parses the input an unmodeled block spelled and puts it into the block itself, as the
         * object the protocol expects when the block is sent back.
         *
         * @return whether the block was rebuilt to carry the input
         */
        private boolean writeInput(RawContentBlock block, StringBuilder input) {
            if (input.isEmpty()) {
                return false;
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
            if (parsed == null) {
                return false;
            }
            Map<String, Object> members = new LinkedHashMap<>(block.getMembers());
            members.put("input", parsed);
            return replace(block, new RawContentBlock(members, block.getExtras()));
        }

        /** The bracket a frame carries for the block it speaks for, or {@code null} when it names none. */
        private static @Nullable Integer indexOf(ChatStreamEvent event) {
            Object index = event.getExtras().get("index");
            return index instanceof Integer block ? block : null;
        }

        /**
         * Merges a fragment into the part its block opened. The kinds are the block's own — a delta
         * never changes what its block is — so the merged part is built as the same kind, carrying
         * the joined value, and takes the old one's place.
         */
        private void mergeInto(ContentPart target, ContentPart fragment) {
            if (target instanceof TextPart text && fragment instanceof TextPart piece) {
                replace(target, new TextPart(join(text.getText(), piece.getText()), text.getExtras()));
            } else if (target instanceof ToolCallPart call && fragment instanceof ToolCallPart piece) {
                String name = call.getName() != null ? call.getName() : piece.getName();
                replace(target, new ToolCallPart(call.getCallId(), name,
                        join(call.getArgumentsJson(), piece.getArgumentsJson()),
                        merge(call.getExtras(), piece.getExtras())));
            } else if (target instanceof ReasoningPart reasoning && fragment instanceof ReasoningPart piece) {
                replace(target, new ReasoningPart(join(reasoning.getText(), piece.getText()),
                        merge(reasoning.getExtras(), piece.getExtras())));
            } else {
                // A fragment whose kind disagrees with the part it names is kept as a part of its own
                // rather than forced into a shape it does not fit.
                parts.add(fragment);
            }
        }

        /**
         * Puts a new instance of a part where the fold holds the old one, and points the block the
         * old one belonged to at the new instance — so a later delta that names the same block finds
         * the part it just built.
         *
         * @return whether the old part was one the fold held
         */
        private boolean replace(ContentPart current, ContentPart replacement) {
            int position = parts.indexOf(current);
            if (position < 0) {
                return false;
            }
            parts.set(position, replacement);
            openBlocks.replaceAll((index, part) -> part == current ? replacement : part);
            return true;
        }

        /**
         * The two bags as one, or whichever is present when the other is not; {@code null} when
         * neither is, so a part nobody configured carries no bag at all.
         */
        private static @Nullable ProviderExtras merge(@Nullable ProviderExtras current,
                @Nullable ProviderExtras addition) {
            if (addition == null || addition.isEmpty()) {
                return current;
            }
            if (current == null) {
                return addition;
            }
            ProviderExtras merged = new ProviderExtras();
            merged.putAll(current);
            merged.putAll(addition);
            return merged;
        }
    }

    /**
     * The event's own extras onto the answer, minus what belongs to the event alone: {@code index}
     * says which block a frame arrived for, and {@code delta} is a frame's own fragment — stream
     * bookkeeping rather than a field of the answer, kept on the event for the application instead
     * of being promoted to a member no response carries.
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

    /** The arguments as they arrive: a fragment is a piece of the JSON text, not a value. */
    private static @Nullable String join(@Nullable String current, @Nullable String fragment) {
        if (fragment == null) {
            return current;
        }
        return current == null ? fragment : current + fragment;
    }

}
