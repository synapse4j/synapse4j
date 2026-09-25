package io.github.synapse4j.openai;

import java.util.List;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.Usage;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonReader;

/**
 * Walks a protocol document from a caller-supplied {@link JsonReader} into the shared model.
 * Methods are opened by the shape of what comes out of them: a whole completion, the payload of
 * one stream frame, or a document that reports a failure. The document is walked token by token
 * rather than decoded into a tree: the fields this module models are mapped as they go by, the
 * ones it does not go into the extras bag of the node they belong to, under the path they came
 * from, so a field the provider adds is neither dropped nor able to break the parse, and the body
 * is decoded once instead of twice.
 *
 * <p>
 * The reader is handed in, and where its bytes come from is not this class's business and must not
 * become so: response headers, the HTTP status and a stream's {@code [DONE]} sentinel all belong
 * to the orchestration layer that opens the reader and drives what happens around it.
 */
class ChatCompletionsReader {

    /**
     * Builds the shared response from the wire document the reader is positioned on. Every field
     * this module does not model is kept rather than dropped: it goes into the extras bag of the node
     * it belongs to, under the path it came from. A document without choices is not an answer, and
     * is refused.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the response
     */
    static ChatResponse read(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            throw new SynapseException("OpenAI chat completion was not a JSON object");
        }
        ChatResponse response = new ChatResponse();
        boolean choicesRead = false;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "id" -> response.setId(reader.string());
                case "model" -> response.setModel(reader.string());
                case "choices" -> {
                    readChoices(reader, response);
                    choicesRead = true;
                }
                case "usage" -> response.setUsage(readUsage(reader));
                default -> response.getExtras().put(field, reader.captureValue());
            }
        }
        if (!choicesRead) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        return response;
    }

    private static void readChoices(JsonReader reader, ChatResponse response) {
        // The reader is on the value the "choices" name introduced, so the array's own start token
        // is the current one rather than the next.
        if (reader.token() != JsonReader.Token.START_ARRAY
                || reader.nextToken() == JsonReader.Token.END_ARRAY) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        // The reader is on the first choice's START_OBJECT here.
        readChoice(reader, response, 0);
        int position = 1;
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            // The shared model carries a single message, so a further choice is not modelled —
            // and is kept whole under its own path rather than thrown away: a provider's words
            // reach extras whenever this library has nowhere of its own to put them.
            response.getExtras().put(List.of("choices", String.valueOf(position)), reader.captureValue());
            position++;
        }
    }

    private static void readChoice(JsonReader reader, ChatResponse response, int position) {
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "finish_reason" -> response.setFinishReason(reader.string());
                case "message" -> readMessage(reader, response.getMessage());
                // A choice field this module does not model keeps the path it came from, so a
                // provider's addition stays readable even though it belongs to a choice.
                default -> response.getExtras().put(List.of("choices", String.valueOf(position), field),
                        reader.captureValue());
            }
        }
    }

    private static void readMessage(JsonReader reader, ChatMessage message) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "role" -> message.setRole(reader.string());
                case "content" -> readContent(reader, message);
                case "tool_calls" -> readToolCalls(reader, message);
                default -> message.getOrCreateExtras().put(field, reader.captureValue());
            }
        }
    }

    private static void readContent(JsonReader reader, ChatMessage message) {
        JsonReader.Token token = reader.token();
        if (token == JsonReader.Token.STRING) {
            String text = reader.string();
            if (!text.isEmpty()) {
                message.getParts().add(new TextPart(text));
            }
            return;
        }
        if (token == JsonReader.Token.START_ARRAY) {
            readContentParts(reader, message);
            return;
        }
        if (token == JsonReader.Token.NULL) {
            // An assistant turn with nothing to say, which is what a tool call looks like.
            return;
        }
        throw new SynapseException("unsupported content shape in OpenAI response: " + describe(token));
    }

    private static void readContentParts(JsonReader reader, ChatMessage message) {
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                // Not a typed part object — the previous binding cast blindly and died here. An
                // element with no type carries nothing mappable, so it is skipped.
                reader.skipValue();
                continue;
            }
            readContentPart(reader, message);
        }
    }

    private static void readContentPart(JsonReader reader, ChatMessage message) {
        String type = null;
        String text = null;
        ProviderExtras collected = new ProviderExtras();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "type" -> type = reader.string();
                case "text" -> text = reader.string();
                default -> collected.put(field, reader.captureValue());
            }
        }
        if (type == null) {
            return;
        }
        if (!"text".equals(type)) {
            throw new SynapseException("unsupported content part in OpenAI response: " + type);
        }
        TextPart part = new TextPart(text);
        // The type that decides what the part is may come after the fields it does not model, so
        // the part is built here and the fields collected on the way move into its own bag.
        part.getOrCreateExtras().putAll(collected);
        message.getParts().add(part);
    }

    private static void readToolCalls(JsonReader reader, ChatMessage message) {
        if (reader.token() != JsonReader.Token.START_ARRAY) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                continue;
            }
            readToolCall(reader, message);
        }
    }

    private static void readToolCall(JsonReader reader, ChatMessage message) {
        ToolCallPart call = new ToolCallPart();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "id" -> call.setCallId(reader.string());
                case "function" -> readToolCallFunction(reader, call);
                default -> call.getOrCreateExtras().put(field, reader.captureValue());
            }
        }
        message.getParts().add(call);
    }

    private static void readToolCallFunction(JsonReader reader, ToolCallPart call) {
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
                default -> call.getOrCreateExtras().put(List.of("function", field), reader.captureValue());
            }
        }
    }

    /**
     * Builds one event from the payload of a single stream frame. The event opens as a chunk and
     * takes the payload's own {@code object} name when it carries one, so a kind this module has
     * never heard of reaches the caller under the name the provider gave it.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the event
     */
    static ChatStreamEvent readEvent(JsonReader reader) {
        ChatStreamEvent event = new ChatStreamEvent();
        event.setEventType(OpenAiEventTypes.CHUNK);
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
                case "usage" -> event.setUsage(readUsage(reader));
                // A failure can arrive as a frame of its own after the answer was accepted, so
                // it is raised here rather than left for the aggregation to notice.
                case "error" -> throw streamError(reader);
                default -> event.getExtras().put(field, reader.captureValue());
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

    private static void readChoices(JsonReader reader, ChatStreamEvent event) {
        if (reader.token() != JsonReader.Token.START_ARRAY) {
            reader.skipValue();
            return;
        }
        // The reader is on the value the "choices" name introduced, so the array's own start token
        // is the current one and the first element arrives with the next call.
        boolean first = true;
        int position = 1;
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            if (reader.token() != JsonReader.Token.START_OBJECT) {
                reader.skipValue();
                continue;
            }
            if (first) {
                readChoice(reader, event, 0);
                first = false;
            } else {
                // Kept whole under its own path, the way the blocking walk keeps it: the shared
                // model has no message for it, but the words still need to arrive.
                event.getExtras().put(List.of("choices", String.valueOf(position)), reader.captureValue());
                position++;
            }
        }
    }

    private static void readChoice(JsonReader reader, ChatStreamEvent event, int position) {
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

    private static void readDelta(JsonReader reader, ChatStreamEvent event) {
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
                default -> delta.getOrCreateExtras().put(field, reader.captureValue());
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

    private static void readDeltaToolCalls(JsonReader reader, ChatMessage delta) {
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

    private static void readDeltaToolCall(JsonReader reader, ChatMessage delta) {
        ToolCallPart call = new ToolCallPart();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "id" -> call.setCallId(reader.string());
                case "function" -> readDeltaToolCallFunction(reader, call);
                default -> call.getOrCreateExtras().put(field, reader.captureValue());
            }
        }
        delta.getParts().add(call);
    }

    private static void readDeltaToolCallFunction(JsonReader reader, ToolCallPart call) {
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
                default -> call.getOrCreateExtras().put(List.of("function", field), reader.captureValue());
            }
        }
    }

    /**
     * Reads the provider's error document into the detail a failure message carries — the part
     * after its own prefix: {@code ": message [type] (code)}, each member the error object has, in
     * that order, and nothing where it has none. A document that carries no error object answers
     * {@code null}: what to say then belongs to the caller, as does everything about how the body
     * reached a reader in the first place.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the detail after the caller's own prefix, or {@code null} when there is none to read
     */
    static String readError(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            return null;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            if ("error".equals(field)) {
                // A scalar where the conventional object belongs says less than the raw body does.
                return reader.token() == JsonReader.Token.START_OBJECT ? errorSuffix(reader) : null;
            }
            reader.skipValue();
        }
        return null;
    }

    /**
     * The exception for a failure the provider reports inside the stream, where no HTTP status is
     * involved: the response was accepted, and the refusal arrives as a frame of its own. The
     * message keeps the provider's own detail, type and code, the way a refused call's does.
     *
     * @param reader the reader, positioned on the error value the frame carries
     * @return the exception to raise
     */
    private static SynapseException streamError(JsonReader reader) {
        StringBuilder message = new StringBuilder("OpenAI stream failed");
        if (reader.token() == JsonReader.Token.START_OBJECT) {
            message.append(errorSuffix(reader));
        } else {
            Object value = reader.captureValue();
            if (value != null) {
                message.append(": ").append(value);
            }
        }
        return new SynapseException(message.toString());
    }

    /**
     * The detail an error object spells out — {@code ": message [type] (code)}, only the members it
     * has — with the reader positioned on the object's start.
     */
    private static String errorSuffix(JsonReader reader) {
        String message = null;
        String type = null;
        String code = null;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "message" -> message = errorText(reader);
                case "type" -> type = errorText(reader);
                case "code" -> code = errorText(reader);
                default -> reader.skipValue();
            }
        }
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

    /** A member's text as the message spells it, or {@code null} where it carries no text. */
    private static String errorText(JsonReader reader) {
        JsonReader.Token token = reader.token();
        if (token == JsonReader.Token.START_OBJECT || token == JsonReader.Token.START_ARRAY) {
            // A structured member has no place in the message, and leaving it unread would lose
            // the walk: skip it the way any unmodelled value is passed over.
            reader.skipValue();
            return null;
        }
        return reader.string();
    }

    /**
     * Reads a usage object into the shared model. The same object arrives in a whole response and in
     * the frame that ends a streamed one, so both directions read it here.
     *
     * @param reader the reader, positioned on the usage value
     * @return the usage
     */
    private static Usage readUsage(JsonReader reader) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return null;
        }
        Usage usage = new Usage();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "prompt_tokens" -> usage.setInputTokens(asInteger(reader));
                case "completion_tokens" -> usage.setOutputTokens(asInteger(reader));
                case "prompt_tokens_details" -> readPromptTokenDetails(reader, usage);
                default -> usage.getExtras().put(field, reader.captureValue());
            }
        }
        return usage;
    }

    private static void readPromptTokenDetails(JsonReader reader, Usage usage) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            if ("cached_tokens".equals(field)) {
                usage.setCachedInputTokens(asInteger(reader));
            } else {
                // A count this module does not model stays under the details object it was nested
                // in, rather than being lifted to the usage level and losing where it came from.
                usage.getExtras().put(List.of("prompt_tokens_details", field), reader.captureValue());
            }
        }
    }

    private static Integer asInteger(JsonReader reader) {
        if (reader.token() != JsonReader.Token.NUMBER) {
            // A count spelled some other way is left alone rather than guessed at; the value still
            // has to be consumed, or the walk would lose its place.
            reader.skipValue();
            return null;
        }
        // The shared model keeps counts in an int, and a token count beyond that is not a real one.
        return (int) reader.longValue();
    }

    private static String describe(JsonReader.Token token) {
        if (token == JsonReader.Token.NUMBER) {
            return "Number";
        }
        if (token == JsonReader.Token.TRUE || token == JsonReader.Token.FALSE) {
            return "Boolean";
        }
        return "unexpected";
    }

}
