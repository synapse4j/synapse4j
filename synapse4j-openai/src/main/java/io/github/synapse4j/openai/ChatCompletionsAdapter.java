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
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.data.Usage;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonView;

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
 * Part types this cut does not support (reasoning, media, ...) fail loudly here rather than being
 * dropped: a request that arrived at the provider incomplete would look like success from above.
 */
class ChatCompletionsAdapter {

    private final JsonCodec codec;

    ChatCompletionsAdapter(JsonCodec codec) {
        this.codec = codec;
    }

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

    ChatResponse fromWire(JsonView root, Map<String, List<String>> httpHeaders) {
        JsonView choices = root.get("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        JsonView choice = choices.get(0);
        ChatResponse response = new ChatResponse();
        response.setId(root.get("id").asText());
        response.setModel(root.get("model").asText());
        response.setFinishReason(choice.get("finish_reason").asText());
        JsonView message = choice.get("message");
        if (message.isObject()) {
            response.getMessage().setRole(message.get("role").asText());
            fromWireContent(message.get("content"), response);
            fromWireToolCalls(message.get("tool_calls"), response);
        }
        JsonView usage = root.get("usage");
        if (usage.isObject()) {
            response.setUsage(fromWireUsage(usage));
        }
        httpHeaders.forEach((name, values) -> response.getHeaders().put(name, String.join(", ", values)));
        return response;
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

    @SuppressWarnings("unchecked")
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

    private void fromWireContent(JsonView content, ChatResponse response) {
        if (content.isMissing() || content.isNull()) {
            return;
        }
        if (content.isText()) {
            String text = content.asText();
            if (!text.isEmpty()) {
                response.getMessage().getParts().add(new TextPart(text));
            }
            return;
        }
        if (content.isArray()) {
            for (JsonView part : content.elements()) {
                String type = part.get("type").asText();
                if (type == null) {
                    // Not a typed part object — the previous binding cast blindly and died here.
                    // An element with no type carries nothing mappable, so it is skipped.
                    continue;
                }
                if (!"text".equals(type)) {
                    throw new SynapseException(
                            "unsupported content part in OpenAI response: " + type);
                }
                response.getMessage().getParts().add(new TextPart(part.get("text").asText()));
            }
            return;
        }
        throw new SynapseException("unsupported content shape in OpenAI response: " + describe(content));
    }

    private void fromWireToolCalls(JsonView toolCalls, ChatResponse response) {
        if (!toolCalls.isArray()) {
            return;
        }
        for (JsonView call : toolCalls.elements()) {
            JsonView function = call.get("function");
            response.getMessage().getParts().add(new ToolCallPart(call.get("id").asText(),
                    function.get("name").asText(), function.get("arguments").asText()));
        }
    }

    private Usage fromWireUsage(JsonView usage) {
        Usage result = new Usage();
        result.setInputTokens(asInteger(usage.get("prompt_tokens")));
        result.setOutputTokens(asInteger(usage.get("completion_tokens")));
        JsonView details = usage.get("prompt_tokens_details");
        if (details.isObject()) {
            result.setCachedInputTokens(asInteger(details.get("cached_tokens")));
        }
        return result;
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

    private static Integer asInteger(JsonView node) {
        Long value = node.asLong();
        return value == null ? null : value.intValue();
    }

    private static String describe(JsonView node) {
        if (node.isNumber()) {
            return "Number";
        }
        if (node.isBoolean()) {
            return "Boolean";
        }
        return "unexpected";
    }

    private SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI: " + part.getClass().getSimpleName());
    }

}
