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
import io.github.synapse4j.data.MediaPart;
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
import io.github.synapse4j.util.Base64Reader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Translates between the shared chat model and the chat-completions wire document. Stateless; holds
 * only the application's codec, for embedding schema strings as parsed maps.
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
 * Responses are read token by token through {@link JsonReader} rather than decoded into a tree: the
 * fields this module models are mapped as they go by, and the ones it does not are captured into the
 * extras bag of the node they belong to, under the path they came from. So a field the provider adds
 * is neither dropped nor able to break the parse, and the body is decoded once instead of twice. In
 * the other direction, every node's extras are merged over the members this module models, so a field
 * a caller adds goes out where it was added, and a name both of them set is the caller's value that
 * goes out.
 *
 * <p>
 * A media part is rendered as the protocol's image content: a URL the provider fetches, or the
 * payload itself inlined as a data URL. The inlined form is written through a {@link Base64Reader},
 * so the bytes are encoded as they are handed to the writer and a payload larger than memory still
 * goes out. Audio, video and documents take a different shape in this protocol and fail loudly here,
 * along with every other part type this cut does not support: a request that arrived at the provider
 * incomplete would look like success from above.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ChatCompletionsAdapter {

    /** The type prefix of the media this module renders; the protocol's image shape takes nothing else. */
    private static final String IMAGE_TYPE_PREFIX = "image/";

    private final JsonCodec codec;

    /**
     * Writes the request as the wire document.
     *
     * @param request the request to translate
     * @param writer  the writer to write into; owned by the caller, and left open
     */
    void writeTo(ChatRequest request, JsonWriter writer) {
        writer.writeValue(document(request, false));
    }

    /**
     * Writes the request for an answer that comes back as a stream. This protocol asks for that
     * with members of the request document rather than with another endpoint or content type.
     *
     * @param request the request to translate
     * @param writer  the writer to write into; owned by the caller, and left open
     */
    void writeStreamingTo(ChatRequest request, JsonWriter writer) {
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
        putIfSet(document, "max_tokens", request.getOptions().getMaxOutputTokens());
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
    private static Map<String, Object> textPart(TextPart part) {
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

    private Map<String, Object> toolResult(ToolResultPart result, ProviderExtras messageExtras) {
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
        // A field the response carried inside the function object comes back to the same path.
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    private List<Map<String, Object>> tools(List<ToolDefinition> definitions) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            tools.add(tool(definition));
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
    private static void putIfSet(Map<String, Object> members, String name, Object value) {
        if (value != null) {
            members.put(name, value);
        }
    }

    /** The bag to record into, created when the node carries none yet. */
    private static ProviderExtras extras(ChatMessage message) {
        ProviderExtras extras = message.getExtras();
        if (extras == null) {
            extras = new ProviderExtras();
            message.setExtras(extras);
        }
        return extras;
    }

    /** The bag to record into, created when the node carries none yet. */
    private static ProviderExtras extras(ContentPart part) {
        ProviderExtras extras = part.getExtras();
        if (extras == null) {
            extras = new ProviderExtras();
            part.setExtras(extras);
        }
        return extras;
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
                case "usage" -> response.setUsage(readUsage(reader));
                default -> response.getExtras().put(field, reader.captureValue());
            }
        }
        if (!choicesRead) {
            throw new SynapseException("OpenAI chat completion contained no choices");
        }
        copyHeaders(response, httpHeaders);
        return response;
    }

    /**
     * Copies the HTTP response headers onto the shared response. The shared model holds one value
     * per name, so several values of a header are joined the way a blocking call joins them — the
     * transport metadata of an answer must not depend on which way it was asked for.
     *
     * @param response    the response to carry the headers
     * @param httpHeaders the response headers, as the transport reports them
     */
    static void copyHeaders(ChatResponse response, Map<String, List<String>> httpHeaders) {
        httpHeaders.forEach((name, values) -> response.getHeaders().put(name, String.join(", ", values)));
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
                default -> extras(message).put(field, reader.captureValue());
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
        extras(part).putAll(collected);
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
                default -> extras(call).put(field, reader.captureValue());
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
                default -> extras(call).put(List.of("function", field), reader.captureValue());
            }
        }
    }

    /**
     * Reads a usage object into the shared model. The same object arrives in a whole response and in
     * the frame that ends a streamed one, so both directions read it here.
     *
     * @param reader the reader, positioned on the usage value
     * @return the usage
     */
    static Usage readUsage(JsonReader reader) {
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
