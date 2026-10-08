package io.github.synapse4j.openai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatFinishReason;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.Usage;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonReader;

/**
 * Walks a protocol document from a caller-supplied {@link JsonReader} into the shared model.
 * Methods are opened by the shape of what comes out of them: a whole response, the payload of one
 * stream frame, or a document that reports a failure. The document is walked token by token rather
 * than decoded into a tree: the fields this module models are mapped as they go by, the ones it does
 * not go into the extras bag of the node they belong to, under the path they came from, so a field
 * the provider adds is neither dropped nor able to break the parse, and the body is decoded once
 * instead of twice.
 *
 * <p>
 * The response is a flat array of items rather than a message with everything in it, so an item that
 * carries nothing this module models — a built-in tool's call, a search result — is kept whole in the
 * response's extras under its own path instead of stopping the walk.
 *
 * <p>
 * The reader is handed in, and where its bytes come from is not this class's business and must not
 * become so: response headers, the HTTP status and the frames a stream arrives in all belong to the
 * orchestration layer that opens the reader and drives what happens around it.
 *
 * <p>
 * One instance walks one exchange. Nothing here varies between endpoints, so unlike the
 * chat-completions reader it holds no configuration: the document is the only thing each call takes,
 * and one exchange reads several of them — a frame per pull on the streaming path, plus a body when
 * something went wrong.
 */
class ResponsesReader {

    /**
     * The payload member naming the item a streamed fragment belongs to, and the name the item
     * announces itself under. This module does not model either, so the item stays in the part's
     * extras — and is read back from there when a fragment has to be matched to its call.
     */
    static final String ITEM_ID = "item_id";

    /** The payload member naming where that item sits in the response, kept beside {@link #ITEM_ID}. */
    static final String OUTPUT_INDEX = "output_index";

    /**
     * Builds the shared response from the wire document the reader is positioned on. Every field
     * this module does not model is kept rather than dropped: it goes into the extras bag of the node
     * it belongs to, under the path it came from.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the response
     */
    ChatResponse read(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            throw new SynapseException("OpenAI response was not a JSON object");
        }
        return readResponse(reader);
    }

    /**
     * Builds one event from the payload of a single stream frame. The event type is the frame's own
     * {@code event} name, or the payload's {@code type} member when the frame carries no name; a
     * kind this module has never heard of reaches the caller under the name the provider gave it,
     * with the whole payload in its extras for the application to read.
     *
     * @param reader    the reader, before its first token; the caller owns it
     * @param eventName the SSE frame's {@code event} name, or {@code null} when it carried none
     * @return the event
     */
    ChatStreamEvent readEvent(JsonReader reader, @Nullable String eventName) {
        ChatStreamEvent event = new ChatStreamEvent();
        if (eventName != null) {
            event.setEventType(eventName);
        }
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            throw new SynapseException("OpenAI stream event was not a JSON object");
        }
        String type = null;
        ChatResponse whole = null;
        ProviderExtras payload = new ProviderExtras();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("response".equals(field) && reader.token() == JsonReader.Token.START_OBJECT) {
                // The frames that carry the whole response are read by the blocking path itself, so
                // a streamed answer is assembled from the same walk a single response goes through.
                whole = readResponse(reader);
                continue;
            }
            Object value = reader.captureValue();
            payload.put(field, value);
            if ("type".equals(field) && value instanceof String payloadType) {
                type = payloadType;
            }
        }
        if (event.getEventType() == null) {
            event.setEventType(type);
        }
        String eventType = event.getEventType();
        if (eventType == null) {
            event.getExtras().putAll(payload);
            return event;
        }
        switch (eventType) {
            case OpenAiResponsesEventTypes.ERROR -> throw streamError(payload);
            case OpenAiResponsesEventTypes.FAILED -> throw failed(payload.get("error"));
            case OpenAiResponsesEventTypes.CREATED, OpenAiResponsesEventTypes.IN_PROGRESS,
                    OpenAiResponsesEventTypes.COMPLETED, OpenAiResponsesEventTypes.INCOMPLETE ->
                carryWhole(event, whole);
            case OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA -> textDelta(event, payload);
            case OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED -> addedItem(event, payload);
            case OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA -> argumentsDelta(event, payload);
            case OpenAiResponsesEventTypes.REASONING_SUMMARY_TEXT_DELTA -> reasoningDelta(event, payload);
            // An event this module does not model still arrives: the payload is handed over whole,
            // because whether it says something is the application's to decide.
            default -> event.getExtras().putAll(payload);
        }
        return event;
    }

    /**
     * Builds the response the reader is positioned on. The reader stands on the object's start, so
     * the same walk serves a whole body and the {@code response} object a streamed frame nests.
     */
    private ChatResponse readResponse(JsonReader reader) {
        ChatResponse response = new ChatResponse();
        // The turn is the model's wherever it is read from, and no item in the output has to say so.
        ChatMessage.Builder message = ChatMessage.builder().role(ChatRole.ASSISTANT);
        ProviderExtras messageExtras = new ProviderExtras();
        String status = null;
        String incompleteReason = null;
        Object error = null;
        boolean calledTool = false;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            switch (field) {
                case "id" -> response.setId(reader.string());
                case "model" -> response.setModel(reader.string());
                case "output" -> calledTool = readOutput(reader, response, message, messageExtras);
                case "status" -> status = reader.string();
                case "incomplete_details" -> incompleteReason = readIncompleteDetails(reader, response);
                case "usage" -> response.setUsage(readUsage(reader));
                case "error" -> error = reader.captureValue();
                default -> response.getExtras().put(field, reader.captureValue());
            }
        }
        if ("failed".equals(status)) {
            // A failed exchange is not an answer with an empty turn; it is the provider refusing to
            // give one, and the detail belongs in the message rather than in an extras bag.
            throw failed(error);
        }
        if (!messageExtras.isEmpty()) {
            message.extras(messageExtras);
        }
        response.setMessage(message.build());
        response.setFinishReason(finishReason(status, calledTool, incompleteReason));
        return response;
    }

    /**
     * The output items as the parts of the answer's turn. The item that carries a tool call is what
     * makes the turn a tool-calling one, so it is reported back rather than left to be found again.
     *
     * @return whether the output carried a tool call
     */
    private static boolean readOutput(JsonReader reader, ChatResponse response, ChatMessage.Builder message,
            ProviderExtras messageExtras) {
        if (reader.token() != JsonReader.Token.START_ARRAY) {
            reader.skipValue();
            return false;
        }
        boolean calledTool = false;
        int position = 0;
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                position++;
                continue;
            }
            calledTool |= readItem(reader, response, message, messageExtras, position);
            position++;
        }
        return calledTool;
    }

    /**
     * One item of the output as the part it becomes. The item's members are collected before it is
     * placed, because the type that says which item this is may come after the fields it carries.
     *
     * @return whether the item was a tool call
     */
    private static boolean readItem(JsonReader reader, ChatResponse response, ChatMessage.Builder message,
            ProviderExtras messageExtras, int position) {
        String type = null;
        Map<String, Object> members = new LinkedHashMap<>();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            Object value = reader.captureValue();
            if ("type".equals(field)) {
                type = stringOf(value);
            } else {
                members.put(field, value);
            }
        }
        if ("message".equals(type)) {
            readMessageItem(members, message, messageExtras);
            return false;
        }
        if ("function_call".equals(type)) {
            readFunctionCallItem(members, message);
            return true;
        }
        if ("reasoning".equals(type)) {
            readReasoningItem(members, message);
            return false;
        }
        // An item this module does not model — a built-in tool's call, a search result — is kept
        // whole under the path it came from rather than dropped: the shared model has no part for it,
        // but its words still have to arrive. It stays on the response, because the message's extras
        // are what a request replays, and this item is not a member of a message.
        Map<String, Object> whole = new LinkedHashMap<>();
        whole.put("type", type);
        whole.putAll(members);
        response.getExtras().put(List.of("output", String.valueOf(position)), whole);
        return false;
    }

    /**
     * A message item: what it says becomes the turn's text, and the item's own identity and status
     * ride in the message's extras — which is what lets the turn be replayed as the item it was.
     */
    private static void readMessageItem(Map<String, Object> members, ChatMessage.Builder message,
            ProviderExtras messageExtras) {
        for (Map.Entry<String, Object> member : members.entrySet()) {
            if ("content".equals(member.getKey())) {
                readMessageContent(member.getValue(), message, messageExtras);
            } else {
                messageExtras.put(member.getKey(), member.getValue());
            }
        }
    }

    private static void readMessageContent(@Nullable Object value, ChatMessage.Builder message,
            ProviderExtras messageExtras) {
        if (!(value instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> members)) {
                continue;
            }
            String type = stringOf(members.get("type"));
            if (type == null) {
                continue;
            }
            if ("refusal".equals(type)) {
                // The refusal this protocol spells inside the content is kept as the member its
                // message-level cousins travel under, so an application reads one member however
                // the protocol chose to deliver the words — and the walk goes on to the parts
                // beside it instead of failing on it.
                messageExtras.put("refusal", members.get("refusal"));
                continue;
            }
            if (!"output_text".equals(type)) {
                throw new SynapseException("unsupported content part in OpenAI Responses response: " + type);
            }
            ProviderExtras extras = new ProviderExtras();
            for (Map.Entry<?, ?> member : members.entrySet()) {
                String name = String.valueOf(member.getKey());
                if (!"type".equals(name) && !"text".equals(name)) {
                    // The citations and log-probabilities the entry carries belong to this text, so
                    // they stay on the part rather than on the message the part sits in.
                    extras.put(name, member.getValue());
                }
            }
            message.part(new TextPart(stringOf(members.get("text")), extras));
        }
    }

    /** A tool call item: this protocol keys it by {@code call_id}, and its id and status ride along. */
    private static void readFunctionCallItem(Map<String, Object> members, ChatMessage.Builder message) {
        String callId = null;
        String callName = null;
        String argumentsJson = null;
        ProviderExtras extras = new ProviderExtras();
        for (Map.Entry<String, Object> member : members.entrySet()) {
            switch (member.getKey()) {
                case "call_id" -> callId = stringOf(member.getValue());
                case "name" -> callName = stringOf(member.getValue());
                case "arguments" -> argumentsJson = stringOf(member.getValue());
                default -> extras.put(member.getKey(), member.getValue());
            }
        }
        message.part(new ToolCallPart(callId, callName, argumentsJson, extras));
    }

    /**
     * A reasoning item: its summary becomes the part's text, and the identity and the encrypted
     * companion the provider attached ride in its extras — a provider that requires its reasoning
     * back will not take the next turn without them.
     */
    private static void readReasoningItem(Map<String, Object> members, ChatMessage.Builder message) {
        String text = null;
        ProviderExtras extras = new ProviderExtras();
        for (Map.Entry<String, Object> member : members.entrySet()) {
            if ("summary".equals(member.getKey())) {
                text = summaryText(member.getValue());
            } else {
                extras.put(member.getKey(), member.getValue());
            }
        }
        if ((text == null || text.isEmpty()) && extras.isEmpty()) {
            // A reasoning item with nothing said and nothing to replay contributes nothing.
            return;
        }
        message.part(new ReasoningPart(text, extras));
    }

    /** The summary's text, as the entries spell it; {@code null} when none of them says anything. */
    private static @Nullable String summaryText(@Nullable Object value) {
        if (!(value instanceof List<?> entries)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> members && members.get("text") instanceof String part) {
                text.append(part);
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * Why generation stopped, from the status the response reports. The reason an incomplete answer
     * gives is this protocol's own vocabulary, so the two that have a neutral equivalent are mapped
     * and anything else is passed through as it stands.
     */
    private static @Nullable String finishReason(@Nullable String status, boolean calledTool,
            @Nullable String incompleteReason) {
        if ("completed".equals(status)) {
            return calledTool ? ChatFinishReason.TOOL_CALLS : ChatFinishReason.STOP;
        }
        if ("incomplete".equals(status)) {
            if ("max_output_tokens".equals(incompleteReason)) {
                return ChatFinishReason.LENGTH;
            }
            if ("content_filter".equals(incompleteReason)) {
                return ChatFinishReason.CONTENT_FILTER;
            }
            return incompleteReason;
        }
        return status;
    }

    /** The reason an incomplete answer stopped, with the rest of the object kept under its path. */
    private static @Nullable String readIncompleteDetails(JsonReader reader, ChatResponse response) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return null;
        }
        String reason = null;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("reason".equals(field)) {
                reason = reader.string();
            } else {
                response.getExtras().put(List.of("incomplete_details", field), reader.captureValue());
            }
        }
        return reason;
    }

    /** Carries a whole response onto the event that reported it. */
    private static void carryWhole(ChatStreamEvent event, @Nullable ChatResponse whole) {
        if (whole == null) {
            return;
        }
        event.setId(whole.getId());
        event.setModel(whole.getModel());
        event.setUsage(whole.getUsage());
        event.setFinishReason(whole.getFinishReason());
        // The response's unmodelled members travel with it, so the fold takes this protocol's own
        // extras from the frames that carry the whole response rather than from the fragments.
        event.getExtras().putAll(whole.getExtras());
        // The whole turn, not a fragment: the fold replaces what the fragments built with it.
        event.setDelta(whole.getMessage());
    }

    private static void textDelta(ChatStreamEvent event, ProviderExtras payload) {
        // The whole frame rides on the event — where in the stream it sat, the fragment it
        // carried — for an application reading the stream. The answer keeps none of it: a
        // blocking response has no frames, and the fold is what makes the two answers equal.
        event.getExtras().putAll(payload);
        String fragment = stringOf(payload.get("delta"));
        if (fragment != null) {
            event.setDelta(delta(new TextPart(fragment)));
        }
    }

    private static void argumentsDelta(ChatStreamEvent event, ProviderExtras payload) {
        // The frame belongs to the event; see textDelta.
        event.getExtras().putAll(payload);
        String fragment = stringOf(payload.get("delta"));
        if (fragment == null) {
            return;
        }
        ProviderExtras extras = new ProviderExtras();
        streamPosition(payload, extras);
        event.setDelta(delta(new ToolCallPart(null, null, fragment, extras)));
    }

    private static void reasoningDelta(ChatStreamEvent event, ProviderExtras payload) {
        // The frame belongs to the event; see textDelta.
        event.getExtras().putAll(payload);
        String fragment = stringOf(payload.get("delta"));
        if (fragment == null) {
            return;
        }
        ProviderExtras extras = new ProviderExtras();
        streamPosition(payload, extras);
        event.setDelta(delta(new ReasoningPart(fragment, extras)));
    }

    /**
     * The item a frame announces, when it is a tool call. The call is named once, on the frame that
     * opens it, and the frames that spell its arguments follow: the identity kept here is what ties
     * the two together, since this module has no field for it.
     */
    private static void addedItem(ChatStreamEvent event, ProviderExtras payload) {
        // The frame belongs to the event; see textDelta.
        event.getExtras().putAll(payload);
        if (payload.get("item") instanceof Map<?, ?> item && "function_call".equals(item.get("type"))) {
            ProviderExtras extras = new ProviderExtras();
            streamPosition(payload, extras);
            // The frame that opens a call sits outside the item, so the item names itself only here —
            // while the frames that spell its arguments name it by the very same id, which is what
            // makes the two halves one call rather than several.
            String itemId = stringOf(item.get("id"));
            if (itemId != null && !extras.contains(ITEM_ID)) {
                extras.put(ITEM_ID, itemId);
            }
            event.setDelta(delta(new ToolCallPart(stringOf(item.get("call_id")), stringOf(item.get("name")),
                    null, extras)));
        }
    }

    /** Keeps the position a fragment came with on the part it produced, where the fold reads it back. */
    private static void streamPosition(ProviderExtras payload, ProviderExtras partExtras) {
        Object itemId = payload.get(ITEM_ID);
        if (itemId != null) {
            partExtras.put(ITEM_ID, itemId);
        }
        Object outputIndex = payload.get(OUTPUT_INDEX);
        if (outputIndex != null) {
            partExtras.put(OUTPUT_INDEX, outputIndex);
        }
    }

    /** A fragment as the one-part turn it is folded through. */
    private static ChatMessage delta(ContentPart part) {
        return new ChatMessage(null, null, part);
    }

    /**
     * The exception for a failed exchange, with the provider's own detail, type and code the way a
     * refused call's carries them.
     */
    private static SynapseException failed(@Nullable Object error) {
        return new SynapseException("OpenAI response failed" + errorDetail(error));
    }

    /**
     * The exception for a failure the provider reports inside the stream, where no HTTP status is
     * involved: the response was accepted, and the refusal arrives as a frame of its own.
     */
    private static SynapseException streamError(ProviderExtras payload) {
        Object error = payload.get("error");
        if (error != null) {
            return new SynapseException("OpenAI stream failed" + errorDetail(error));
        }
        // This protocol's error event spells its members at the top level, where `type` names the
        // event rather than the failure, so that one is left out of the detail.
        Map<String, Object> members = new LinkedHashMap<>(payload.nestedMap());
        members.remove("type");
        return new SynapseException("OpenAI stream failed" + errorDetail(members));
    }

    /**
     * The detail an error object spells out — {@code ": message [type] (code)}, only the members it
     * has.
     */
    private static String errorDetail(@Nullable Object error) {
        if (!(error instanceof Map<?, ?> members)) {
            return error == null ? "" : ": " + error;
        }
        String message = stringOf(members.get("message"));
        String type = stringOf(members.get("type"));
        String code = stringOf(members.get("code"));
        StringBuilder detail = new StringBuilder();
        if (message != null) {
            detail.append(": ").append(message);
        }
        if (type != null) {
            detail.append(" [").append(type).append(']');
        }
        if (code != null) {
            detail.append(" (").append(code).append(')');
        }
        return detail.toString();
    }

    /**
     * Reads a usage object into the shared model. The same object arrives in a whole response and in
     * the frame that ends a streamed one, so both directions read it here.
     *
     * @param reader the reader, positioned on the usage value
     * @return the usage
     */
    private static @Nullable Usage readUsage(JsonReader reader) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return null;
        }
        Usage usage = new Usage();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            switch (field) {
                case "input_tokens" -> usage.setInputTokens(asInteger(reader));
                case "output_tokens" -> usage.setOutputTokens(asInteger(reader));
                case "input_tokens_details" -> readInputTokenDetails(reader, usage);
                case "output_tokens_details" -> readOutputTokenDetails(reader, usage);
                default -> usage.getExtras().put(field, reader.captureValue());
            }
        }
        return usage;
    }

    private static void readInputTokenDetails(JsonReader reader, Usage usage) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("cached_tokens".equals(field)) {
                usage.setCachedInputTokens(asInteger(reader));
            } else {
                // A count this module does not model stays under the details object it was nested
                // in, rather than being lifted to the usage level and losing where it came from.
                usage.getExtras().put(List.of("input_tokens_details", field), reader.captureValue());
            }
        }
    }

    /**
     * The counts of what was generated: none of them is modelled here, but each keeps the details
     * object it was nested in, so a reasoning count stays readable under the path it came from.
     */
    private static void readOutputTokenDetails(JsonReader reader, Usage usage) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            usage.getExtras().put(List.of("output_tokens_details", field), reader.captureValue());
        }
    }

    private static @Nullable Integer asInteger(JsonReader reader) {
        if (reader.token() != JsonReader.Token.NUMBER) {
            // A count spelled some other way is left alone rather than guessed at; the value still
            // has to be consumed, or the walk would lose its place.
            reader.skipValue();
            return null;
        }
        // The shared model keeps counts in an int, and a token count beyond that is not a real one.
        return (int) reader.longValue();
    }

    /** The text of a captured value, or {@code null} when it is not text. */
    private static @Nullable String stringOf(@Nullable Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * The property name the reader is on, for the member loops above: a loop over an object's
     * members only runs while the reader is on a name, so a null here is a broken reader rather
     * than a document without that name.
     */
    private static String name(JsonReader reader) {
        return Objects.requireNonNull(reader.name(), "the reader is not on a property name");
    }

}
