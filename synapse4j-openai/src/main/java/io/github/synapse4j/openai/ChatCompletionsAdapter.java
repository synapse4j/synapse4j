package io.github.synapse4j.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Translates between the shared chat model and the chat-completions wire document. Stateless; holds
 * only the application's codec, for embedding schema strings as parsed maps.
 *
 * <p>
 * The wire document is built as plain maps and lists with literal keys: a map key cannot be
 * renamed by a codec's naming strategy and absent entries are never emitted, so the bytes that
 * leave here are exactly the protocol's spelling regardless of which JSON library binds them.
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

    Map<String, Object> toWire(ChatRequest request) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("model", request.getOptions().getModel());
        List<Object> messages = new ArrayList<>();
        for (ChatMessage message : request.getMessages()) {
            messages.addAll(toWireMessages(message));
        }
        wire.put("messages", messages);
        if (!request.getTools().isEmpty()) {
            wire.put("tools", request.getTools().stream().map(this::toWireTool).toList());
        }
        putIfNotNull(wire, "temperature", request.getOptions().getTemperature());
        putIfNotNull(wire, "max_tokens", request.getOptions().getMaxOutputTokens());
        putIfNotNull(wire, "top_p", request.getOptions().getTopP());
        Map<String, Object> responseFormat = toWireResponseFormat(request.getResponseFormat());
        if (responseFormat != null) {
            wire.put("response_format", responseFormat);
        }
        wire.putAll(request.getOptions().getExtras().toNestedMap());
        return wire;
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

    private List<Map<String, Object>> toWireMessages(ChatMessage message) {
        boolean hasToolResult = message.getParts().stream().anyMatch(ToolResultPart.class::isInstance);
        if (hasToolResult) {
            if (!message.getParts().stream().allMatch(ToolResultPart.class::isInstance)) {
                throw new SynapseException(
                        "unsupported message for OpenAI: tool results mixed with other parts");
            }
            return message.getParts().stream().map(part -> toWireToolResult((ToolResultPart) part)).toList();
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.getRole());
        StringBuilder text = new StringBuilder();
        List<Object> toolCalls = new ArrayList<>();
        boolean arrayForm = false;
        for (ContentPart part : message.getParts()) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.getText());
            } else if (part instanceof ToolCallPart toolCall) {
                // A tool call in a request is the model's earlier turn being replayed; it forces
                // the array form, since the message is no longer text-only.
                arrayForm = true;
                toolCalls.add(toWireToolCall(toolCall));
            } else {
                throw unsupportedPart(part);
            }
        }
        if (arrayForm) {
            List<Object> content = new ArrayList<>();
            if (text.length() > 0) {
                Map<String, Object> contentPart = new LinkedHashMap<>();
                contentPart.put("type", "text");
                contentPart.put("text", text.toString());
                content.add(contentPart);
            }
            wire.put("content", content);
        } else if (text.length() > 0) {
            wire.put("content", text.toString());
        }
        if (!toolCalls.isEmpty()) {
            wire.put("tool_calls", toolCalls);
        }
        return List.of(wire);
    }

    private Map<String, Object> toWireToolResult(ToolResultPart result) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", "tool");
        wire.put("content", textOf(result.getParts()));
        wire.put("tool_call_id", result.getCallId());
        return wire;
    }

    private Map<String, Object> toWireToolCall(ToolCallPart part) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", part.getName());
        function.put("arguments", part.getArgumentsJson());
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", part.getCallId());
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
    }

    private Map<String, Object> toWireTool(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.getName());
        function.put("description", definition.getDescription());
        Map<String, Object> parameters = parseSchema(definition.getInputSchema());
        if (parameters != null) {
            function.put("parameters", parameters);
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
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

    private Map<String, Object> toWireResponseFormat(ChatResponseFormat format) {
        if (format.getType() == null) {
            return null;
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(format.getType())) {
            wire.put("type", "json_schema");
            String name = format.getName() != null ? format.getName() : "response";
            // "strict" is deliberately not sent in this cut: it changes how strictly the provider
            // enforces the schema, and choosing that for the caller would be a silent behaviour
            // decision. It stays reachable through the format's extras.
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", name);
            Map<String, Object> schema = parseSchema(format.getSchema());
            if (schema != null) {
                jsonSchema.put("schema", schema);
            }
            wire.put("json_schema", jsonSchema);
            return wire;
        }
        wire.put("type", ChatResponseFormat.TYPE_JSON.equals(format.getType()) ? "json_object" : format.getType());
        return wire;
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

    private static void putIfNotNull(Map<String, Object> wire, String key, Object value) {
        if (value != null) {
            wire.put(key, value);
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

    private SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI: " + part.getClass().getSimpleName());
    }

}
