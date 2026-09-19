package io.github.synapse4j.openai;

import java.util.ArrayList;
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
import io.github.synapse4j.openai.wire.ChatCompletionRequest;
import io.github.synapse4j.openai.wire.ChatCompletionResponse;
import io.github.synapse4j.openai.wire.Choice;
import io.github.synapse4j.openai.wire.Message;
import io.github.synapse4j.openai.wire.ResponseFormat;
import io.github.synapse4j.openai.wire.Tool;
import io.github.synapse4j.openai.wire.ToolCall;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;

/**
 * Translates between the shared chat model and the chat-completions wire model. Stateless; holds
 * only the mapper, for parsing schema strings into nodes.
 *
 * <p>
 * Part types this cut does not support (reasoning, media, ...) fail loudly here rather than being
 * dropped: a request that arrived at the provider incomplete would look like success from above.
 */
class ChatCompletionsAdapter {

    private final JsonMapper mapper;

    ChatCompletionsAdapter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    ChatCompletionRequest toWire(ChatRequest request) {
        ChatCompletionRequest wire = new ChatCompletionRequest();
        wire.setModel(request.getOptions().getModel());
        List<Message> messages = new ArrayList<>();
        for (ChatMessage message : request.getMessages()) {
            messages.addAll(toWireMessages(message));
        }
        wire.setMessages(messages);
        if (!request.getTools().isEmpty()) {
            wire.setTools(request.getTools().stream().map(this::toWireTool).toList());
        }
        wire.setTemperature(request.getOptions().getTemperature());
        wire.setMaxTokens(request.getOptions().getMaxOutputTokens());
        wire.setTopP(request.getOptions().getTopP());
        wire.setResponseFormat(toWireResponseFormat(request.getResponseFormat()));
        wire.getExtras().putAll(request.getOptions().getExtras().toNestedMap());
        return wire;
    }

    ChatResponse fromWire(ChatCompletionResponse wire, Map<String, List<String>> httpHeaders) {
        if (wire.getChoices() == null || wire.getChoices().isEmpty()) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        Choice choice = wire.getChoices().get(0);
        ChatResponse response = new ChatResponse();
        response.setId(wire.getId());
        response.setModel(wire.getModel());
        response.setFinishReason(choice.getFinishReason());
        if (choice.getMessage() != null) {
            response.getMessage().setRole(choice.getMessage().getRole());
            fromWireContent(choice.getMessage().getContent(), response);
            fromWireToolCalls(choice.getMessage().getToolCalls(), response);
        }
        if (wire.getUsage() != null) {
            response.setUsage(fromWireUsage(wire.getUsage()));
        }
        httpHeaders.forEach((name, values) -> response.getHeaders().put(name, String.join(", ", values)));
        return response;
    }

    private List<Message> toWireMessages(ChatMessage message) {
        boolean hasToolResult = message.getParts().stream().anyMatch(ToolResultPart.class::isInstance);
        if (hasToolResult) {
            if (!message.getParts().stream().allMatch(ToolResultPart.class::isInstance)) {
                throw new SynapseException(
                        "unsupported message for OpenAI: tool results mixed with other parts");
            }
            return message.getParts().stream().map(part -> toWireToolResult((ToolResultPart) part)).toList();
        }
        Message wire = new Message();
        wire.setRole(message.getRole());
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
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
            List<io.github.synapse4j.openai.wire.ContentPart> content = new ArrayList<>();
            if (text.length() > 0) {
                io.github.synapse4j.openai.wire.ContentPart contentPart = new io.github.synapse4j.openai.wire.ContentPart();
                contentPart.setType("text");
                contentPart.setText(text.toString());
                content.add(contentPart);
            }
            wire.setContent(content);
        } else if (text.length() > 0) {
            wire.setContent(text.toString());
        }
        if (!toolCalls.isEmpty()) {
            wire.setToolCalls(toolCalls);
        }
        return List.of(wire);
    }

    private Message toWireToolResult(ToolResultPart result) {
        Message wire = new Message();
        wire.setRole("tool");
        wire.setToolCallId(result.getCallId());
        wire.setContent(textOf(result.getParts()));
        return wire;
    }

    private ToolCall toWireToolCall(ToolCallPart part) {
        ToolCall wire = new ToolCall();
        wire.setId(part.getCallId());
        wire.setType("function");
        ToolCall.Function function = new ToolCall.Function();
        function.setName(part.getName());
        function.setArguments(part.getArgumentsJson());
        wire.setFunction(function);
        return wire;
    }

    private Tool toWireTool(ToolDefinition definition) {
        Tool tool = new Tool();
        tool.setType("function");
        Tool.Function function = new Tool.Function();
        function.setName(definition.getName());
        function.setDescription(definition.getDescription());
        function.setParameters(parseSchema(definition.getInputSchema()));
        tool.setFunction(function);
        return tool;
    }

    private JsonNode parseSchema(String schema) {
        if (schema == null) {
            return null;
        }
        try {
            return mapper.readTree(schema);
        } catch (JacksonException e) {
            throw new SynapseException("tool input schema is not valid JSON", e);
        }
    }

    private ResponseFormat toWireResponseFormat(ChatResponseFormat format) {
        if (format.getType() == null) {
            return null;
        }
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(format.getType())) {
            ResponseFormat wire = new ResponseFormat();
            wire.setType("json_schema");
            String name = format.getName() != null ? format.getName() : "response";
            // "strict" is deliberately not sent in this cut: it changes how strictly the provider
            // enforces the schema, and choosing that for the caller would be a silent behaviour
            // decision. It stays reachable through the format's extras.
            ResponseFormat.JsonSchema jsonSchema = new ResponseFormat.JsonSchema();
            jsonSchema.setName(name);
            jsonSchema.setSchema(parseSchema(format.getSchema()));
            wire.setJsonSchema(jsonSchema);
            return wire;
        }
        ResponseFormat wire = new ResponseFormat();
        wire.setType(ChatResponseFormat.TYPE_JSON.equals(format.getType()) ? "json_object" : format.getType());
        return wire;
    }

    private void fromWireContent(Object content, ChatResponse response) {
        if (content == null) {
            return;
        }
        if (content instanceof String text) {
            if (!text.isEmpty()) {
                response.getMessage().getParts().add(new TextPart(text));
            }
            return;
        }
        if (content instanceof List<?> parts) {
            for (Object part : parts) {
                io.github.synapse4j.openai.wire.ContentPart wire = mapper.convertValue(part,
                        io.github.synapse4j.openai.wire.ContentPart.class);
                if (!"text".equals(wire.getType())) {
                    throw new SynapseException(
                            "unsupported content part in OpenAI response: " + wire.getType());
                }
                response.getMessage().getParts().add(new TextPart(wire.getText()));
            }
            return;
        }
        throw new SynapseException(
                "unsupported content shape in OpenAI response: " + content.getClass().getSimpleName());
    }

    private void fromWireToolCalls(List<ToolCall> toolCalls, ChatResponse response) {
        if (toolCalls == null) {
            return;
        }
        for (ToolCall wire : toolCalls) {
            response.getMessage().getParts().add(
                    new ToolCallPart(wire.getId(), wire.getFunction().getName(),
                            wire.getFunction().getArguments()));
        }
    }

    private Usage fromWireUsage(io.github.synapse4j.openai.wire.Usage wire) {
        Usage usage = new Usage();
        usage.setInputTokens(wire.getPromptTokens());
        usage.setOutputTokens(wire.getCompletionTokens());
        if (wire.getPromptTokensDetails() != null) {
            usage.setCachedInputTokens(wire.getPromptTokensDetails().getCachedTokens());
        }
        return usage;
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

    private SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI: " + part.getClass().getSimpleName());
    }

}
