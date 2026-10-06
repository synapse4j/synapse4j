package io.github.synapse4j.anthropic;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatFinishReason;
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
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Walks a protocol document from a caller-supplied {@link JsonReader} into the shared model.
 * Methods are opened by the shape of what comes out of them: a whole message, or the payload of one
 * stream frame. The document is walked token by token rather than decoded into a tree: the fields
 * this module models are mapped as they go by, the ones it does not go into the extras bag of the
 * node they belong to, under the path they came from, so a field the provider adds is neither
 * dropped nor able to break the parse.
 *
 * <p>
 * This protocol's response <em>is</em> the message — one object carrying the id, the role, the
 * content blocks, why it stopped and what it consumed — so a member the module does not model is a
 * member of the answer itself, and content blocks that have no counterpart in the shared part
 * family are kept whole under the path they came from in the content array. A block whose type
 * names the model's reasoning keeps its readable text in a {@link ReasoningPart} and everything
 * opaque — the signature, the redacted body — in that part's extras, because the endpoint requires
 * both back unchanged on the next turn.
 *
 * <p>
 * The counts are normalized into what they mean rather than as the protocol spells them: this
 * endpoint splits the input three ways (uncached, read from cache, written to cache) and the shared
 * model wants the whole input with the cached part as a child of it, so the reader sums the split
 * and keeps the pieces under their own names where the model has no field for them. Cross-provider
 * numbers only compare if every provider's counts mean the same thing here.
 *
 * <p>
 * The reader is handed in, and where its bytes come from is not this class's business: response
 * headers, the HTTP status and the frames a stream arrives in all belong to the orchestration layer.
 * One instance walks one exchange — several documents on the streaming path — and holds only the
 * codec, which is what turns a tool input's parsed object back into the JSON text the shared model
 * keeps.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class MessagesReader {

    private final JsonCodec codec;

    /**
     * Builds the shared response from the wire document the reader is positioned on. Every field
     * this module does not model is kept rather than dropped: it goes into the extras bag of the
     * node it belongs to, under the path it came from. A document without content is not an answer,
     * and is refused.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the response
     */
    ChatResponse read(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            throw new SynapseException("Anthropic Messages response was not a JSON object");
        }
        return readMessage(reader);
    }

    /**
     * Builds one event from the payload of a single stream frame. The event type is the frame's own
     * {@code event} name, falling back to the payload's {@code type} member when the frame carries
     * no name; a kind this module has never heard of reaches the caller under the name the provider
     * gave it, with whatever it carries in the event's extras. An {@code error} frame raises the
     * failure it reports: the answer was accepted, and the refusal arrives as a frame of its own.
     *
     * @param reader    the reader, before its first token; the caller owns it
     * @param eventName the SSE frame's {@code event} name, or {@code null} when it carried none
     * @return the event
     */
    ChatStreamEvent readEvent(JsonReader reader, @Nullable String eventName) {
        ChatStreamEvent event = new ChatStreamEvent();
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            throw new SynapseException("Anthropic stream event was not a JSON object");
        }
        String type = null;
        Integer index = null;
        ChatResponse whole = null;
        ProviderExtras payload = new ProviderExtras();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("type".equals(field)) {
                // The frame's discriminator, not a member of the answer: it becomes the event's own
                // type and never reaches the assembled answer.
                type = reader.string();
                continue;
            }
            if ("index".equals(field) && reader.token() == JsonReader.Token.NUMBER) {
                index = (int) reader.longValue();
                continue;
            }
            if ("message".equals(field) && reader.token() == JsonReader.Token.START_OBJECT) {
                // The frame that opens the answer carries the message document whole, so it goes
                // through the same walk a blocking response does.
                whole = readMessage(reader);
                continue;
            }
            payload.put(field, reader.captureValue());
        }
        if (eventName != null) {
            event.setEventType(eventName);
        } else {
            event.setEventType(type);
        }
        if (index != null) {
            // The block bracket's position, kept on the event where an application dispatching
            // frames can read it — and left off the answer, whose document has no such member.
            event.getExtras().put("index", index);
        }
        String eventType = event.getEventType();
        if (eventType == null) {
            event.getExtras().putAll(payload);
            return event;
        }
        switch (eventType) {
            case AnthropicEventTypes.ERROR -> throw streamError(payload);
            case AnthropicEventTypes.MESSAGE_START -> {
                carryWhole(event, whole);
                carryRest(event, payload);
            }
            case AnthropicEventTypes.CONTENT_BLOCK_START -> contentBlockStart(event, payload);
            case AnthropicEventTypes.CONTENT_BLOCK_DELTA -> contentBlockDelta(event, payload);
            case AnthropicEventTypes.MESSAGE_DELTA -> messageDelta(event, payload);
            // content_block_stop, message_stop, ping and any kind this module has never heard of
            // carry no normalized content: whatever they spell beyond the discriminator stays on
            // the event, because whether it says something is the application's to decide.
            default -> carryRest(event, payload);
        }
        return event;
    }

    /**
     * Builds the response the reader is positioned on — the message document, which for a blocking
     * answer is the whole body and for a streamed one is the object nested under
     * {@code message_start}. The reader stands on the object's start, so the same walk serves both.
     * A message document without content is not an answer and is refused: an empty turn that never
     * asked to be empty would pass for one.
     */
    private ChatResponse readMessage(JsonReader reader) {
        ChatResponse response = new ChatResponse();
        ChatMessage message = response.getMessage();
        boolean contentRead = false;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            switch (field) {
                case "id" -> response.setId(reader.string());
                case "model" -> response.setModel(reader.string());
                case "role" -> message.setRole(reader.string());
                case "content" -> {
                    readContent(reader, response);
                    contentRead = true;
                }
                case "stop_reason" -> response.setFinishReason(finishReason(reader.string()));
                case "usage" -> response.setUsage(readUsage(reader.captureValue()));
                default -> response.getExtras().put(field, reader.captureValue());
            }
        }
        if (!contentRead) {
            throw new SynapseException("Anthropic Messages response contained no content");
        }
        return response;
    }

    /** The content array as the parts of the turn; a block with no part of its own keeps its place. */
    private void readContent(JsonReader reader, ChatResponse response) {
        JsonReader.Token token = reader.token();
        if (token == JsonReader.Token.STRING) {
            String text = Objects.requireNonNull(reader.string(), "a string token carries a string");
            if (!text.isEmpty()) {
                response.getMessage().getParts().add(new TextPart(text));
            }
            return;
        }
        if (token == JsonReader.Token.NULL) {
            return;
        }
        if (token != JsonReader.Token.START_ARRAY) {
            throw new SynapseException(
                    "unsupported content shape in Anthropic Messages response: " + describe(token));
        }
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                continue;
            }
            Object value = reader.captureValue();
            if (value instanceof Map<?, ?> block) {
                // A block this module does not model — a server tool's call, a search result —
                // rides as itself rather than being dropped or reinterpreted: kept whole as a
                // part, it sits where it sat in the content array, so it travels with the turn
                // when the conversation continues and goes back out exactly as it arrived.
                ContentPart part = partOf(block);
                response.getMessage().getParts().add(part != null ? part : new RawContentBlock(block));
            }
        }
    }

    /**
     * One content block as the part it becomes, or {@code null} when this module has no part for
     * the block's type. Everything the part does not model moves into its extras under the name it
     * came in, so a field the provider adds to a block rides with that block alone — and for the
     * reasoning blocks the extras are also where the opaque companions live, keyed by the protocol's
     * spelling so they go back exactly as they arrived.
     */
    private @Nullable ContentPart partOf(Map<?, ?> block) {
        if (!(block.get("type") instanceof String type)) {
            return null;
        }
        switch (type) {
            case "text" -> {
                TextPart text = new TextPart(stringOf(block.get("text")));
                copyRest(block, text, "type", "text");
                return text;
            }
            case "tool_use" -> {
                ToolCallPart call = new ToolCallPart();
                call.setCallId(stringOf(block.get("id")));
                call.setName(stringOf(block.get("name")));
                Object input = block.get("input");
                if (input != null) {
                    // The wire carries the arguments as an object; the shared model keeps them as
                    // the JSON text they spell, so the codec the application chose writes them out.
                    call.setArgumentsJson(codec.encode(input));
                }
                copyRest(block, call, "type", "id", "name", "input");
                return call;
            }
            case "thinking" -> {
                ReasoningPart reasoning = new ReasoningPart();
                reasoning.setText(stringOf(block.get("thinking")));
                // The signature stays out of the part's fields and lands in its extras: the shared
                // model attaches no typed field to reasoning, and the next turn needs this one back.
                copyRest(block, reasoning, "type", "thinking");
                return reasoning;
            }
            case "redacted_thinking" -> {
                // Safety-redacted reasoning with nothing readable in it. The whole block — its type
                // included — rides in the part's extras, so the writer can spell the block back as
                // itself: the endpoint refuses a continuation whose thinking blocks were lost.
                ReasoningPart reasoning = new ReasoningPart();
                copyRest(block, reasoning);
                return reasoning;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Moves every member of a content block the part does not model into the part's extras, under
     * the name it came in.
     *
     * @param block   the block as the wire spelled it
     * @param part    the part the block became
     * @param modeled the member names the part models itself; they stay out of the extras
     */
    private static void copyRest(Map<?, ?> block, ContentPart part, String... modeled) {
        for (Map.Entry<?, ?> member : block.entrySet()) {
            String key = String.valueOf(member.getKey());
            boolean taken = false;
            for (String name : modeled) {
                if (name.equals(key)) {
                    taken = true;
                }
            }
            if (!taken) {
                part.getOrCreateExtras().put(key, member.getValue());
            }
        }
    }

    /**
     * Carries a whole response from {@code message_start} onto the event that reported it. The
     * frame opens the answer rather than adding to it, so its message — still empty of content —
     * is what gives the event the answer's identity, counts and unmodelled members.
     */
    private static void carryWhole(ChatStreamEvent event, @Nullable ChatResponse whole) {
        if (whole == null) {
            return;
        }
        event.setId(whole.getId());
        event.setModel(whole.getModel());
        event.setUsage(whole.getUsage());
        event.setFinishReason(whole.getFinishReason());
        event.getExtras().putAll(whole.getExtras());
        event.setDelta(whole.getMessage());
    }

    /** The block a {@code content_block_start} frame opens, as the part it begins. */
    private void contentBlockStart(ChatStreamEvent event, ProviderExtras payload) {
        Object blockValue = payload.get("content_block");
        ContentPart part = blockValue instanceof Map<?, ?> block ? partOf(block) : null;
        if (part instanceof ToolCallPart call) {
            // The input the opening frame carries is the empty object the deltas then fill; the
            // arguments arrive as fragments from here on, so the placeholder is not kept as text.
            call.setArgumentsJson(null);
            event.setDelta(delta(call));
        } else if (part != null) {
            event.setDelta(delta(part));
        } else if (blockValue instanceof Map<?, ?> block) {
            // A block type with no part of its own is born the same way the blocking walk keeps
            // it: whole, as itself, in the place it holds in the turn — so both ways of asking
            // read the same document, and the input the deltas spell joins it when it closes.
            event.setDelta(delta(new RawContentBlock(block)));
        }
        carryRest(event, payload, "content_block");
    }

    /** The piece a {@code content_block_delta} frame adds, as a one-part fragment of the turn. */
    private void contentBlockDelta(ChatStreamEvent event, ProviderExtras payload) {
        Object deltaValue = payload.get("delta");
        ContentPart part = deltaValue instanceof Map<?, ?> members ? deltaPart(members) : null;
        if (part == null) {
            // A delta kind this module has never heard of arrives whole, under the member it came
            // in on, because whether it says something is the application's to decide.
            carryRest(event, payload);
            return;
        }
        event.setDelta(delta(part));
        carryRest(event, payload, "delta");
    }

    /** One delta of a content block, or {@code null} when it carries no fragment this module models. */
    private static @Nullable ContentPart deltaPart(Map<?, ?> members) {
        String type = stringOf(members.get("type"));
        if (type == null) {
            return null;
        }
        switch (type) {
            case "text_delta" -> {
                String fragment = stringOf(members.get("text"));
                return fragment != null ? new TextPart(fragment) : null;
            }
            case "input_json_delta" -> {
                // The arguments arrive as fragments of the JSON text, never as values: they are
                // concatenated as they come and only complete when the block closes.
                String fragment = stringOf(members.get("partial_json"));
                if (fragment == null) {
                    return null;
                }
                ToolCallPart call = new ToolCallPart();
                call.setArgumentsJson(fragment);
                return call;
            }
            case "thinking_delta" -> {
                String fragment = stringOf(members.get("thinking"));
                if (fragment == null) {
                    return null;
                }
                ReasoningPart reasoning = new ReasoningPart();
                reasoning.setText(fragment);
                return reasoning;
            }
            case "signature_delta" -> {
                // The signature closes the block it belongs to and lives in the part's extras, so
                // the fold merges it into the reasoning the fragments built — one block, signature
                // included, the way a blocking call reads it.
                Object signature = members.get("signature");
                if (signature == null) {
                    return null;
                }
                ReasoningPart reasoning = new ReasoningPart();
                reasoning.getOrCreateExtras().put("signature", signature);
                return reasoning;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The top-level changes a {@code message_delta} frame reports: why the answer stopped, and the
     * counts as of this frame. Everything the frame's delta carries besides the reason is a member
     * of the answer's document wherever it nests, so it is lifted to the event's own extras and
     * folds to where a blocking call keeps it.
     */
    private static void messageDelta(ChatStreamEvent event, ProviderExtras payload) {
        Object deltaValue = payload.get("delta");
        if (deltaValue instanceof Map<?, ?> members) {
            for (Map.Entry<?, ?> member : members.entrySet()) {
                String field = String.valueOf(member.getKey());
                if ("stop_reason".equals(field)) {
                    String reason = stringOf(member.getValue());
                    // A frame that reports no reason yet is still generating, and a null one is
                    // not a reason.
                    if (reason != null) {
                        event.setFinishReason(finishReason(reason));
                    }
                } else {
                    event.getExtras().put(field, member.getValue());
                }
            }
        }
        Usage usage = readUsage(payload.get("usage"));
        if (usage != null) {
            event.setUsage(usage);
        }
        carryRest(event, payload, "delta", "usage");
    }

    /**
     * The event's remaining payload members, moved onto the event under the names they came in.
     *
     * @param consumed members the dispatch above already read; they are not repeated here
     */
    private static void carryRest(ChatStreamEvent event, ProviderExtras payload, String... consumed) {
        for (Map.Entry<String, Object> member : payload.rawMap().entrySet()) {
            boolean taken = false;
            for (String key : consumed) {
                if (key.equals(member.getKey())) {
                    taken = true;
                }
            }
            if (!taken) {
                event.getExtras().putRaw(member.getKey(), member.getValue());
            }
        }
    }

    /** A fragment as the one-part turn it is folded through. */
    private static ChatMessage delta(ContentPart part) {
        ChatMessage delta = new ChatMessage();
        delta.getParts().add(part);
        return delta;
    }

    /**
     * Why generation stopped, from the stop reason the message reports. The two the shared
     * vocabulary spells — finishing on its own, running into the output limit, stopping to call
     * tools — are mapped onto its constants, and so is a refusal, which is this protocol's word for
     * an answer the provider cut short on purpose. The stop sequence ends the answer as a finish
     * does, with the matched sequence left in the extras under its own name for whoever cares which
     * one it was. Everything else — {@code pause_turn} above all — passes through as it stands:
     * no constant here means the same thing, and renaming it would say more than the protocol did.
     */
    private static @Nullable String finishReason(@Nullable String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn" -> ChatFinishReason.STOP;
            case "max_tokens" -> ChatFinishReason.LENGTH;
            case "tool_use" -> ChatFinishReason.TOOL_CALLS;
            case "refusal" -> ChatFinishReason.CONTENT_FILTER;
            case "stop_sequence" -> ChatFinishReason.STOP;
            default -> stopReason;
        };
    }

    /**
     * Reads a usage document into the shared model. This endpoint splits what the request sent into
     * three counts — tokens never cached, tokens read from the cache, tokens written to it — and
     * the shared model takes one: every input token, with the cached part as a child bucket of it.
     * So the three are summed into {@link Usage}'s input tokens, the cache read becomes its
     * cached-input tokens, and the cache write — counted in the total but named by
     * no field of its own — keeps its original name in the extras, like every other count the model
     * does not carry.
     */
    private static @Nullable Usage readUsage(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> members)) {
            return null;
        }
        Usage usage = new Usage();
        Integer uncached = null;
        Integer cached = null;
        Integer written = null;
        for (Map.Entry<?, ?> member : members.entrySet()) {
            String field = String.valueOf(member.getKey());
            switch (field) {
                case "input_tokens" -> uncached = asInteger(member.getValue());
                case "output_tokens" -> usage.setOutputTokens(asInteger(member.getValue()));
                case "cache_read_input_tokens" -> cached = asInteger(member.getValue());
                case "cache_creation_input_tokens" -> {
                    written = asInteger(member.getValue());
                    // Already inside the summed input, but under no field of its own: the original
                    // name keeps it readable, the way every unmodelled count is kept.
                    usage.getExtras().put(field, member.getValue());
                }
                default -> usage.getExtras().put(field, member.getValue());
            }
        }
        if (uncached != null || cached != null || written != null) {
            usage.setInputTokens(total(uncached) + total(cached) + total(written));
        }
        if (cached != null) {
            usage.setCachedInputTokens(cached);
        }
        return usage;
    }

    private static int total(@Nullable Integer count) {
        return count == null ? 0 : count;
    }

    private static @Nullable Integer asInteger(@Nullable Object value) {
        // A count spelled some other way is left alone rather than guessed at; the value still has
        // somewhere to go — the extras, for the ones that have a name there.
        return value instanceof Number number ? (int) number.longValue() : null;
    }

    /**
     * The exception for a failure the provider reports inside the stream, where no HTTP status is
     * involved: the response was accepted, and the refusal arrives as a frame of its own. The
     * message keeps the provider's own detail and type, the way a refused call's does.
     *
     * @param payload the error frame's payload, discriminator aside
     * @return the exception to raise
     */
    private static SynapseException streamError(ProviderExtras payload) {
        StringBuilder message = new StringBuilder("Anthropic stream failed");
        Object error = payload.get("error");
        if (error instanceof Map<?, ?> members) {
            message.append(errorDetail(members));
        } else if (error != null) {
            message.append(": ").append(error);
        }
        return new SynapseException(message.toString());
    }

    /**
     * The detail an error object spells out — {@code ": message [type]}, only the members it has.
     * Package-visible because a refusal's message renders the same document: one spelling, one
     * shape, reached from both the HTTP status path and the stream.
     *
     * @param error the error object
     * @return the detail after the caller's own prefix; never {@code null}
     */
    static String errorDetail(Map<?, ?> error) {
        String message = stringOf(error.get("message"));
        String type = stringOf(error.get("type"));
        StringBuilder detail = new StringBuilder();
        if (message != null) {
            detail.append(": ").append(message);
        }
        if (type != null) {
            detail.append(" [").append(type).append(']');
        }
        return detail.toString();
    }

    /** The text of a value, or {@code null} when it is not text. */
    private static @Nullable String stringOf(@Nullable Object value) {
        return value instanceof String text ? text : null;
    }

    private static String describe(JsonReader.@Nullable Token token) {
        if (token == null) {
            return "nothing";
        }
        return switch (token) {
            case START_OBJECT -> "an object";
            case START_ARRAY -> "an array";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case TRUE, FALSE -> "a boolean";
            case NULL -> "null";
            default -> token.toString();
        };
    }

    /**
     * The property name the reader is on, for the member loops below: a loop over an object's
     * members only runs while the reader is on a name, so a null here is a broken reader rather
     * than a document without that name.
     */
    private static String name(JsonReader reader) {
        return Objects.requireNonNull(reader.name(), "the reader is not on a property name");
    }

}
