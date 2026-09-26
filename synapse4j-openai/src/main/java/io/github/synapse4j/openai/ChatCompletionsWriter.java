package io.github.synapse4j.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.MediaPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonWriter;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;
import io.github.synapse4j.util.Base64Reader;
import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Writes the shared chat model as the chat-completions wire document. Stateless; holds only the
 * application's codec, for embedding schema strings as parsed maps.
 *
 * <p>
 * A request is assembled as the object it goes out as — one map per node, the members this module
 * models written into it with the node's extras merged over them — and written in one pass. The maps
 * hold references and a media payload stays a reader, so neither the document nor a payload is
 * materialized, and every name is a literal spelled out here — no codec's naming strategy can rename
 * one, and a member whose value is not set is never emitted. So the bytes that leave here are exactly
 * the protocol's spelling whichever JSON library the application chose, and the payload is held once
 * instead of being built and then serialized.
 *
 * <p>
 * A media part is rendered as the protocol's image content: a URL the provider fetches, or the
 * payload itself inlined as a data URL. The inlined form is written through a {@link Base64Reader},
 * so the bytes are encoded as they are handed to the writer and a payload larger than memory still
 * goes out. Audio, video and documents take a different shape in this protocol and fail loudly here,
 * along with every other part type this cut does not support: a request that arrived at the provider
 * incomplete would look like success from above.
 *
 * <p>
 * The {@link JsonWriter} is handed in, and where its bytes go is the caller's affair — the sink,
 * the retries that rewrite the document, the headers that ride beside it. This class knows the
 * document and nothing about the exchange that carries it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ChatCompletionsWriter {

    /** The type prefix of the media this module renders; the protocol's image shape takes nothing else. */
    private static final String IMAGE_TYPE_PREFIX = "image/";

    private final JsonCodec codec;

    /**
     * Writes the request as the wire document.
     *
     * @param request the request to translate
     * @param writer  the writer to write into; owned by the caller, and left open
     */
    void write(ChatRequest request, JsonWriter writer) {
        writer.writeValue(document(request, false));
    }

    /**
     * Writes the request for an answer that comes back as a stream. This protocol asks for that
     * with members of the request document rather than with another endpoint or content type.
     *
     * @param request the request to translate
     * @param writer  the writer to write into; owned by the caller, and left open
     */
    void writeStreaming(ChatRequest request, JsonWriter writer) {
        writer.writeValue(document(request, true));
    }

    /** The whole request as the object it goes out as. */
    private Map<String, Object> document(ChatRequest request, boolean stream) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("model", request.getOptions().getModel());
        document.put("messages", messages(request.getMessages()));
        if (!request.getTools().isEmpty()) {
            document.put("tools", tools(request.getTools()));
        }
        putIfSet(document, "temperature", request.getOptions().getTemperature());
        // The modern name for the limit; an endpoint that only answers to the legacy one gets it
        // through OpenAiCustomizers.legacyMaxTokens().
        putIfSet(document, "max_completion_tokens", request.getOptions().getMaxOutputTokens());
        putIfSet(document, "top_p", request.getOptions().getTopP());
        if (request.getResponseFormat().getType() != null
                || !request.getResponseFormat().getExtras().isEmpty()) {
            document.put("response_format", responseFormat(request.getResponseFormat()));
        }
        if (stream) {
            document.put("stream", true);
            // A streamed answer reports what it consumed in a frame of its own, and only when the
            // request asks for it; without this the assembled answer would carry no counts at all.
            Map<String, Object> streamOptions = new LinkedHashMap<>();
            streamOptions.put("include_usage", true);
            document.put("stream_options", streamOptions);
        }
        // The extras of the request itself merge into the document's own members, so a path lands as
        // a member of this object rather than a level below it. Merged last, so a path set on both
        // sides is the caller's value that goes out.
        request.getOptions().getExtras().mergeInto(document);
        return document;
    }

    /**
     * One message into the array of messages. A message of tool results becomes one entry per result,
     * since the protocol has no message carrying several.
     */
    private List<Map<String, Object>> messages(List<ChatMessage> messages) {
        List<Map<String, Object>> written = new ArrayList<>();
        for (ChatMessage message : messages) {
            written.addAll(entries(message));
        }
        return written;
    }

    private List<Map<String, Object>> entries(ChatMessage message) {
        boolean hasToolResult = message.getParts().stream().anyMatch(ToolResultPart.class::isInstance);
        if (!hasToolResult) {
            return List.of(message(message));
        }
        if (!message.getParts().stream().allMatch(ToolResultPart.class::isInstance)) {
            throw new SynapseException("unsupported message for OpenAI: tool results mixed with other parts");
        }
        // One message becomes one entry per result, so a field set on the message goes onto every
        // entry it turns into.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ContentPart part : message.getParts()) {
            entries.add(toolResult((ToolResultPart) part, message.getExtras()));
        }
        return entries;
    }

    private Map<String, Object> message(ChatMessage message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", message.getRole());
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (arrayContent(message)) {
            List<Map<String, Object>> content = new ArrayList<>();
            for (ContentPart part : message.getParts()) {
                if (part instanceof TextPart textPart) {
                    Map<String, Object> text = textPart(textPart);
                    if (text != null) {
                        content.add(text);
                    }
                } else if (part instanceof MediaPart mediaPart) {
                    content.add(mediaPart(mediaPart));
                } else if (part instanceof ToolCallPart toolCall) {
                    // A tool call is a member of the message rather than an entry of its content,
                    // so it is held back and written beside the array.
                    toolCalls.add(toolCall(toolCall));
                } else {
                    throw unsupportedPart(part);
                }
            }
            entry.put("content", content);
        } else {
            StringBuilder text = new StringBuilder();
            for (ContentPart part : message.getParts()) {
                if (!(part instanceof TextPart textPart)) {
                    throw unsupportedPart(part);
                }
                text.append(textPart.getText());
            }
            if (text.length() > 0) {
                entry.put("content", text.toString());
            }
        }
        if (!toolCalls.isEmpty()) {
            entry.put("tool_calls", toolCalls);
        }
        ProviderExtras messageExtras = message.getExtras();
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * Whether the message takes the array form of content: it does as soon as it is not text alone,
     * because neither an image nor a replayed tool call can be spelled inside a string — nor can a
     * field this module does not model.
     */
    private static boolean arrayContent(ChatMessage message) {
        return message.getParts().stream().anyMatch(part -> part instanceof MediaPart
                || part instanceof ToolCallPart
                || (part.getExtras() != null && !part.getExtras().isEmpty()));
    }

    /**
     * A text part as the entry it becomes, or {@code null} when it carries neither text nor extras —
     * a part with nothing to say, which the protocol has no place for.
     */
    private static @Nullable Map<String, Object> textPart(TextPart part) {
        boolean hasText = part.getText() != null && !part.getText().isEmpty();
        if (!hasText && (part.getExtras() == null || part.getExtras().isEmpty())) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "text");
        if (hasText) {
            entry.put("text", part.getText());
        }
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * A media part in the protocol's image shape. A URL is passed through as it stands: the provider
     * fetches it, and the payload's type is then the provider's to discover. A payload is inlined as
     * a data URL instead, which is where the type has to be spelled out — a data URL is the only
     * thing that declares it.
     */
    private static Map<String, Object> mediaPart(MediaPart part) {
        String mediaType = part.getMediaType();
        boolean typeStated = mediaType != null && !mediaType.isEmpty();
        if (typeStated && !mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
            throw new SynapseException("unsupported media type for OpenAI: " + mediaType);
        }
        if (part.getUri() == null && part.getSource() == null) {
            throw new SynapseException(
                    "unsupported media part for OpenAI: neither uri nor source is set");
        }
        if (part.getUri() == null && !typeStated) {
            throw new SynapseException(
                    "unsupported media part for OpenAI: mediaType is required to inline the payload");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "image_url");
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", part.getUri() != null ? part.getUri()
                : new Base64Reader("data:" + mediaType + ";base64,", part.getSource()));
        entry.put("image_url", imageUrl);
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    private Map<String, Object> toolResult(ToolResultPart result, @Nullable ProviderExtras messageExtras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "tool");
        entry.put("content", textOf(result.getParts()));
        entry.put("tool_call_id", result.getCallId());
        // The message's extras first, the result's own after: the more specific node is merged last,
        // so it wins where both set the same path.
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras resultExtras = result.getExtras();
        if (resultExtras != null) {
            resultExtras.mergeInto(entry);
        }
        return entry;
    }

    private Map<String, Object> toolCall(ToolCallPart part) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", part.getCallId());
        entry.put("type", "function");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", part.getName());
        function.put("arguments", part.getArgumentsJson());
        entry.put("function", function);
        // A field the response carried inside the function object comes back to the same path —
        // except the chunk's association index, which the fold kept only long enough to match
        // fragments to their call and a request has no member to carry.
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            withoutChunkIndex(partExtras).mergeInto(entry);
        }
        return entry;
    }

    /** The call's extras minus {@link OpenAiFields#TOOL_CALL_INDEX} — the original is left alone. */
    private static ProviderExtras withoutChunkIndex(ProviderExtras extras) {
        if (!extras.rawMap().containsKey(OpenAiFields.TOOL_CALL_INDEX)) {
            return extras;
        }
        ProviderExtras rest = new ProviderExtras();
        for (Map.Entry<String, Object> member : extras.rawMap().entrySet()) {
            if (!OpenAiFields.TOOL_CALL_INDEX.equals(member.getKey())) {
                rest.putRaw(member.getKey(), member.getValue());
            }
        }
        return rest;
    }

    private List<Map<String, Object>> tools(List<Tool> requestTools) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Tool requestTool : requestTools) {
            tools.add(tool(requestTool.definition()));
        }
        return tools;
    }

    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.getName());
        function.put("description", definition.getDescription());
        putIfSet(function, "parameters", parseSchema(definition.getInputSchema()));
        // This protocol carries the enforcement flag inside the function object, beside the schema.
        putIfSet(function, "strict", definition.getStrict());

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        definition.getExtras().mergeInto(tool);
        return tool;
    }

    /**
     * The response format as the object it goes out as; the caller only asks for one when it states
     * something.
     */
    private Map<String, Object> responseFormat(ChatResponseFormat format) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(format.getType())) {
            entry.put("type", "json_schema");
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", format.getName() != null ? format.getName() : "response");
            putIfSet(jsonSchema, "description", format.getDescription());
            putIfSet(jsonSchema, "strict", format.getStrict());
            Map<String, Object> schema = parseSchema(format.getSchema());
            if (schema != null) {
                jsonSchema.put("schema", schema);
            }
            entry.put("json_schema", jsonSchema);
        } else if (format.getType() != null) {
            entry.put("type",
                    ChatResponseFormat.TYPE_JSON.equals(format.getType()) ? "json_object" : format.getType());
        }
        format.getExtras().mergeInto(entry);
        return entry;
    }

    /** Puts a member, or nothing at all when the value is not set. */
    private static void putIfSet(Map<String, Object> members, String name, @Nullable Object value) {
        if (value != null) {
            members.put(name, value);
        }
    }

    private @Nullable Map<String, Object> parseSchema(@Nullable String schema) {
        if (schema == null) {
            return null;
        }
        try {
            return codec.decode(schema, Map.class);
        } catch (RuntimeException e) {
            throw new SynapseException("tool input schema is not valid JSON", e);
        }
    }

    private String textOf(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (!(part instanceof TextPart textPart)) {
                throw unsupportedPart(part);
            }
            if (textPart.getExtras() != null && !textPart.getExtras().isEmpty()) {
                throw new SynapseException(
                        "unsupported part for OpenAI: a tool result's content is a string, which cannot carry extras");
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
