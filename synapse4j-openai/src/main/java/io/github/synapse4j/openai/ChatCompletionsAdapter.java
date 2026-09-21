package io.github.synapse4j.openai;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.data.Usage;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonWriter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Translates between the shared chat model and the chat-completions wire document. Stateless; holds
 * only the application's codec, for embedding schema strings as parsed maps.
 *
 * <p>
 * A request is written straight into the caller's {@link JsonWriter}, token by token: the document
 * never exists as a structure, and every name is a literal spelled out here — no codec's naming
 * strategy can rename one, and a member whose value is not set is never emitted. So the bytes that
 * leave here are exactly the protocol's spelling whichever JSON library the application chose, and
 * the payload is held once instead of being built and then serialized.
 *
 * <p>
 * Responses are read token by token through {@link JsonReader} rather than decoded into a tree: the
 * fields this module models are mapped as they go by, and the ones it does not are captured into the
 * extras bag of the node they belong to, under the path they came from. So a field the provider adds
 * is neither dropped nor able to break the parse, and the body is decoded once instead of twice.
 *
 * <p>
 * Part types this cut does not support (reasoning, media, ...) fail loudly here rather than being
 * dropped: a request that arrived at the provider incomplete would look like success from above.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ChatCompletionsAdapter {

    private final JsonCodec codec;

    /**
     * Writes the request as the wire document, in the order the protocol spells it.
     *
     * @param request the request to translate
     * @param writer  the writer to write into; owned by the caller, and left open
     */
    void writeTo(ChatRequest request, JsonWriter writer) {
        writer.writeStartObject();
        writer.writeName("model");
        writeValue(request.getOptions().getModel(), writer);
        writer.writeName("messages");
        writer.writeStartArray();
        for (ChatMessage message : request.getMessages()) {
            writeMessages(message, writer);
        }
        writer.writeEndArray();
        if (!request.getTools().isEmpty()) {
            writer.writeName("tools");
            writer.writeStartArray();
            for (ToolDefinition tool : request.getTools()) {
                writeTool(tool, writer);
            }
            writer.writeEndArray();
        }
        writeMemberIfNotNull(writer, "temperature", request.getOptions().getTemperature());
        writeMemberIfNotNull(writer, "max_tokens", request.getOptions().getMaxOutputTokens());
        writeMemberIfNotNull(writer, "top_p", request.getOptions().getTopP());
        writeResponseFormat(request.getResponseFormat(), writer);
        // The extras of the request itself are its top-level members: a nested bag would nest the
        // protocol's own fields one level too deep.
        writeMembers(request.getOptions().getExtras().toNestedMap(), writer);
        writer.writeEndObject();
    }

    /**
     * Builds the shared response from the wire document the reader is positioned on. Every field
     * this module does not model is kept rather than dropped: it goes into the extras bag of the node
     * it belongs to, under the path it came from.
     *
     * @param reader      the reader, before its first token; the caller owns it
     * @param httpHeaders the response headers, copied onto the response
     * @return the response
     */
    ChatResponse fromWire(JsonReader reader, Map<String, List<String>> httpHeaders) {
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
                case "usage" -> readUsage(reader, response);
                default -> response.getExtras().put(field, reader.captureValue());
            }
        }
        if (!choicesRead) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        httpHeaders.forEach((name, values) -> response.getHeaders().put(name, String.join(", ", values)));
        return response;
    }

    private void readChoices(JsonReader reader, ChatResponse response) {
        // The reader is on the value the "choices" name introduced, so the array's own start token
        // is the current one rather than the next.
        if (reader.token() != JsonReader.Token.START_ARRAY
                || reader.nextToken() == JsonReader.Token.END_ARRAY) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        // The reader is on the first choice's START_OBJECT here.
        readChoice(reader, response, 0);
        while (reader.nextToken() != JsonReader.Token.END_ARRAY) {
            // The shared model carries a single message, so a further choice is not modelled and
            // there is nowhere to put it.
            reader.skipValue();
        }
    }

    private void readChoice(JsonReader reader, ChatResponse response, int position) {
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

    private void readMessage(JsonReader reader, ChatMessage message) {
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
                default -> message.getExtras().put(field, reader.captureValue());
            }
        }
    }

    private void readContent(JsonReader reader, ChatMessage message) {
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

    private void readContentParts(JsonReader reader, ChatMessage message) {
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

    private void readContentPart(JsonReader reader, ChatMessage message) {
        String type = null;
        String text = null;
        ProviderExtras extras = new ProviderExtras();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "type" -> type = reader.string();
                case "text" -> text = reader.string();
                default -> extras.put(field, reader.captureValue());
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
        part.getExtras().putAll(extras);
        message.getParts().add(part);
    }

    private void readToolCalls(JsonReader reader, ChatMessage message) {
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

    private void readToolCall(JsonReader reader, ChatMessage message) {
        ToolCallPart call = new ToolCallPart();
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = reader.name();
            reader.nextToken();
            switch (field) {
                case "id" -> call.setCallId(reader.string());
                case "function" -> readToolCallFunction(reader, call);
                default -> call.getExtras().put(field, reader.captureValue());
            }
        }
        message.getParts().add(call);
    }

    private void readToolCallFunction(JsonReader reader, ToolCallPart call) {
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
                default -> call.getExtras().put(List.of("function", field), reader.captureValue());
            }
        }
    }

    private void readUsage(JsonReader reader, ChatResponse response) {
        if (reader.token() != JsonReader.Token.START_OBJECT) {
            reader.skipValue();
            return;
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
        response.setUsage(usage);
    }

    private void readPromptTokenDetails(JsonReader reader, Usage usage) {
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

    /**
     * Writes one message into the open array of messages. A message of tool results becomes one
     * entry per result, since the protocol has no message carrying several.
     */
    private void writeMessages(ChatMessage message, JsonWriter writer) {
        boolean hasToolResult = message.getParts().stream().anyMatch(ToolResultPart.class::isInstance);
        if (hasToolResult) {
            if (!message.getParts().stream().allMatch(ToolResultPart.class::isInstance)) {
                throw new SynapseException(
                        "unsupported message for OpenAI: tool results mixed with other parts");
            }
            for (ContentPart part : message.getParts()) {
                writeToolResult((ToolResultPart) part, writer);
            }
            return;
        }
        writeMessage(message, writer);
    }

    private void writeMessage(ChatMessage message, JsonWriter writer) {
        writer.writeStartObject();
        writer.writeName("role");
        writeValue(message.getRole(), writer);
        StringBuilder text = new StringBuilder();
        List<ToolCallPart> toolCalls = new ArrayList<>();
        boolean arrayForm = false;
        for (ContentPart part : message.getParts()) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.getText());
            } else if (part instanceof ToolCallPart toolCall) {
                // A tool call in a request is the model's earlier turn being replayed; it forces
                // the array form, since the message is no longer text-only.
                arrayForm = true;
                toolCalls.add(toolCall);
            } else {
                throw unsupportedPart(part);
            }
        }
        if (arrayForm) {
            writer.writeName("content");
            writer.writeStartArray();
            if (text.length() > 0) {
                writer.writeStartObject();
                writer.writeName("type");
                writer.writeString("text");
                writer.writeName("text");
                writer.writeString(text.toString());
                writer.writeEndObject();
            }
            writer.writeEndArray();
        } else if (text.length() > 0) {
            writer.writeName("content");
            writer.writeString(text.toString());
        }
        if (!toolCalls.isEmpty()) {
            writer.writeName("tool_calls");
            writer.writeStartArray();
            for (ToolCallPart call : toolCalls) {
                writeToolCall(call, writer);
            }
            writer.writeEndArray();
        }
        writer.writeEndObject();
    }

    private void writeToolResult(ToolResultPart result, JsonWriter writer) {
        writer.writeStartObject();
        writer.writeName("role");
        writer.writeString("tool");
        writer.writeName("content");
        writer.writeString(textOf(result.getParts()));
        writer.writeName("tool_call_id");
        writeValue(result.getCallId(), writer);
        writer.writeEndObject();
    }

    private void writeToolCall(ToolCallPart part, JsonWriter writer) {
        writer.writeStartObject();
        writer.writeName("id");
        writeValue(part.getCallId(), writer);
        writer.writeName("type");
        writer.writeString("function");
        writer.writeName("function");
        writer.writeStartObject();
        writer.writeName("name");
        writeValue(part.getName(), writer);
        writer.writeName("arguments");
        writeValue(part.getArgumentsJson(), writer);
        writer.writeEndObject();
        writer.writeEndObject();
    }

    private void writeTool(ToolDefinition definition, JsonWriter writer) {
        writer.writeStartObject();
        writer.writeName("type");
        writer.writeString("function");
        writer.writeName("function");
        writer.writeStartObject();
        writer.writeName("name");
        writeValue(definition.getName(), writer);
        writer.writeName("description");
        writeValue(definition.getDescription(), writer);
        Map<String, Object> parameters = parseSchema(definition.getInputSchema());
        if (parameters != null) {
            writer.writeName("parameters");
            writeValue(parameters, writer);
        }
        writer.writeEndObject();
        writer.writeEndObject();
    }

    private Map<String, Object> parseSchema(String schema) {
        if (schema == null) {
            return null;
        }
        try {
            return codec.decode(schema, Map.class);
        } catch (RuntimeException e) {
            throw new SynapseException("tool input schema is not valid JSON", e);
        }
    }

    /** Writes the response format, or nothing at all when the format states no type. */
    private void writeResponseFormat(ChatResponseFormat format, JsonWriter writer) {
        if (format.getType() == null) {
            return;
        }
        writer.writeName("response_format");
        writer.writeStartObject();
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(format.getType())) {
            writer.writeName("type");
            writer.writeString("json_schema");
            String name = format.getName() != null ? format.getName() : "response";
            // "strict" is deliberately not sent in this cut: it changes how strictly the provider
            // enforces the schema, and choosing that for the caller would be a silent behaviour
            // decision. It stays reachable through the format's extras.
            writer.writeName("json_schema");
            writer.writeStartObject();
            writer.writeName("name");
            writer.writeString(name);
            Map<String, Object> schema = parseSchema(format.getSchema());
            if (schema != null) {
                writer.writeName("schema");
                writeValue(schema, writer);
            }
            writer.writeEndObject();
            writer.writeEndObject();
            return;
        }
        writer.writeName("type");
        writer.writeString(ChatResponseFormat.TYPE_JSON.equals(format.getType()) ? "json_object" : format.getType());
        writer.writeEndObject();
    }

    private String textOf(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (!(part instanceof TextPart textPart)) {
                throw unsupportedPart(part);
            }
            text.append(textPart.getText());
        }
        return text.toString();
    }

    /** Writes a member, or nothing at all when the value is not set. */
    private static void writeMemberIfNotNull(JsonWriter writer, String name, Object value) {
        if (value != null) {
            writer.writeName(name);
            writeValue(value, writer);
        }
    }

    /** Writes the members of a nested map into the object that is currently open. */
    private static void writeMembers(Map<String, Object> members, JsonWriter writer) {
        for (Map.Entry<String, Object> member : members.entrySet()) {
            writer.writeName(member.getKey());
            writeValue(member.getValue(), writer);
        }
    }

    /**
     * Writes a value that came from outside this module — an extra, or a schema parsed out of its
     * text. Only the JSON shapes are accepted; anything else is a value this module cannot spell and
     * fails loudly rather than being dropped from the request.
     */
    private static void writeValue(Object value, JsonWriter writer) {
        if (value == null) {
            writer.writeNull();
        } else if (value instanceof String text) {
            writer.writeString(text);
        } else if (value instanceof Boolean flag) {
            writer.writeBoolean(flag);
        } else if (value instanceof Map<?, ?> object) {
            writer.writeStartObject();
            for (Map.Entry<?, ?> member : object.entrySet()) {
                writer.writeName(String.valueOf(member.getKey()));
                writeValue(member.getValue(), writer);
            }
            writer.writeEndObject();
        } else if (value instanceof List<?> array) {
            writer.writeStartArray();
            for (Object element : array) {
                writeValue(element, writer);
            }
            writer.writeEndArray();
        } else if (value instanceof Number number) {
            writeNumber(number, writer);
        } else {
            throw new SynapseException(
                    "unsupported value type for OpenAI: " + value.getClass().getName());
        }
    }

    private static void writeNumber(Number number, JsonWriter writer) {
        if (number instanceof Double || number instanceof Float || number instanceof BigDecimal) {
            writer.writeNumber(number.doubleValue());
            return;
        }
        if (number instanceof BigInteger integer) {
            try {
                writer.writeNumber(integer.longValueExact());
            } catch (ArithmeticException e) {
                // longValue() would truncate silently and put a different number on the wire.
                throw new SynapseException("integer value does not fit in a JSON number: " + integer, e);
            }
            return;
        }
        writer.writeNumber(number.longValue());
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

    private SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI: " + part.getClass().getSimpleName());
    }

}
