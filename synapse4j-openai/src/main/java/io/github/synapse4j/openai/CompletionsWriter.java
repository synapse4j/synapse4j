package io.github.synapse4j.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ListPart;
import io.github.synapse4j.data.MediaPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.RawPart;
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSource;
import io.github.synapse4j.json.JsonWriter;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;
import io.github.synapse4j.util.Base64Reader;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Writes the shared chat model as the chat-completions wire document. Stateless; one instance
 * writes one exchange, and it holds what the whole exchange writes by — the endpoint's conventions,
 * for the members whose name varies between servers. The document being written is the opposite case
 * and stays an argument of each call: it is the thing that varies.
 *
 * <p>
 * A request is assembled as the object it goes out as — one map per node, the members this module
 * models written into it with the node's extras merged over them — and written in one pass. The
 * conversation is the one member that grows with the exchange, so it is not gathered into a list here:
 * its entries are produced as the writer walks them, a message at a time. The maps hold references and
 * a media payload stays a reader, so neither the document nor a payload is materialized, and every name
 * is a literal spelled out here — no codec's naming strategy can rename one, and a member whose value is
 * not set is never emitted. So the bytes that leave here are exactly the protocol's spelling whichever
 * JSON library the application chose, and the payload is held once instead of being built and then
 * serialized.
 *
 * <p>
 * A media part is rendered as the protocol's image content: a URL the provider fetches, or the
 * payload itself inlined as a data URL. The inlined form is written through a {@link Base64Reader},
 * so the bytes are encoded as they are handed to the writer and a payload larger than memory still
 * goes out. Audio, video and documents take a different shape in this protocol and fail loudly here,
 * along with every other part type this cut does not support: a request that arrived at the provider
 * incomplete would look like success from above. A group of parts has no layer of its own here, so
 * it goes out as the parts it holds, in their place.
 *
 * <p>
 * The {@link JsonWriter} is handed in, and where its bytes go is the caller's affair — the sink,
 * the retries that rewrite the document, the headers that ride beside it. This class knows the
 * document and nothing about the exchange that carries it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CompletionsWriter {

    /** The type prefix of the media this module renders; the protocol's image shape takes nothing else. */
    private static final String IMAGE_TYPE_PREFIX = "image/";

    /** The endpoint's conventions, as they were when this exchange began. */
    @NonNull
    private final OpenAiConfig config;

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
        putIfSet(document, "tool_choice", toolChoice(request.getOptions()));
        putIfSet(document, "temperature", request.getOptions().getTemperature());
        if (!config.getMaxTokensField().isBlank()) {
            putIfSet(document, config.getMaxTokensField(), request.getOptions().getMaxOutputTokens());
        }
        putIfSet(document, "top_p", request.getOptions().getTopP());
        putIfSet(document, "reasoning_effort", request.getOptions().getReasoningEffort());
        if (stream) {
            document.put("stream", true);
            // A streamed answer reports what it consumed in a frame of its own, and only when the
            // request asks for it; without this the assembled answer would carry no counts at all.
            Map<String, Object> streamOptions = new LinkedHashMap<>();
            streamOptions.put("include_usage", true);
            document.put("stream_options", streamOptions);
        }
        // The extras of the request itself merge into the document's own members, so a path lands as
        // a member of this object rather than a level below it. They merge before the members that
        // carry extras of their own — the conversation, the tools, the response format: the reach of
        // an entry ends where it meets one of those, so each goes in after the merge, unless the entry
        // names it whole and stands in its place.
        ProviderExtras extras = request.getOptions().getExtras();
        extras.mergeInto(document);
        if (!extras.contains("messages")) {
            // Left as a source: the entries are produced as the writer walks the array rather than
            // gathered into a list first, so a conversation longer than memory still goes out.
            document.put("messages", JsonSource.of(() -> entries(request)));
        }
        if (!request.getTools().isEmpty() && !extras.contains("tools")) {
            document.put("tools", JsonSource.of(() -> tools(request.getTools())));
        }
        ChatResponseFormat format = request.getOptions().getResponseFormat();
        if ((format.getType() != null || !format.getExtras().isEmpty()) && !extras.contains("response_format")) {
            document.put("response_format", responseFormat(format));
        }
        return document;
    }

    /**
     * The whole conversation as the entries it goes out as, in order: the system message first, only
     * when this call carries one, so it keeps the place it had when the conversation was a single list,
     * then the history, then the pending messages. A message of tool results becomes one entry per
     * result, since the protocol has no message carrying several.
     */
    private Stream<Map<String, Object>> entries(ChatRequest request) {
        Stream<ChatMessage> messages = Stream.concat(
                request.getHistoryMessages().stream(), request.getPendingMessages().stream());
        Stream<Map<String, Object>> entries = messages.flatMap(message -> entries(message).stream());
        ChatMessage system = request.getSystemMessage();
        if (system == null) {
            return entries;
        }
        // The system slot leads, as a message of this protocol's own: the slot — not the message —
        // is what says it is the system's, so its role is written here rather than read off it.
        Map<String, Object> entry = message(system, flatten(system.getParts()));
        entry.put("role", "system");
        return Stream.concat(Stream.of(entry), entries);
    }

    private List<Map<String, Object>> entries(ChatMessage message) {
        List<ContentPart> parts = flatten(message.getParts());
        boolean hasToolResult = parts.stream().anyMatch(ToolResultPart.class::isInstance);
        if (!hasToolResult) {
            return List.of(message(message, parts));
        }
        if (!parts.stream().allMatch(ToolResultPart.class::isInstance)) {
            throw new SynapseException("unsupported message for OpenAI: tool results mixed with other parts");
        }
        // One message becomes one entry per result, so a field set on the message goes onto every
        // entry it turns into.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ContentPart part : parts) {
            entries.add(toolResult((ToolResultPart) part, message.getExtras()));
        }
        return entries;
    }

    private Map<String, Object> message(ChatMessage message, List<ContentPart> parts) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfSet(entry, "role", message.getRole());
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Object content = content(parts, toolCalls);
        writeReasoning(parts, entry);
        // The content and the tool calls carry extras of their own, so the message's extras' reach
        // ends here: each goes in after the merge, unless the message names it whole.
        ProviderExtras messageExtras = message.getExtras();
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        if (content != null && !namesWhole(messageExtras, "content")) {
            entry.put("content", content);
        }
        if (!toolCalls.isEmpty() && !namesWhole(messageExtras, "tool_calls")) {
            entry.put("tool_calls", toolCalls);
        }
        return entry;
    }

    /**
     * The message's content as this protocol spells it — the array of parts, or the string the text
     * parts join into — or {@code null} when it carries none. A tool call is a member of the message
     * rather than an entry of its content, so it is collected into the given list instead.
     */
    private @Nullable Object content(List<ContentPart> parts, List<Map<String, Object>> toolCalls) {
        if (!arrayContent(parts)) {
            StringBuilder text = new StringBuilder();
            for (ContentPart part : parts) {
                if (part instanceof TextPart textPart) {
                    // A part with no text says nothing; appending it would spell the member "null".
                    if (textPart.getText() != null) {
                        text.append(textPart.getText());
                    }
                } else if (!(part instanceof ReasoningPart)) {
                    throw unsupportedPart(part);
                }
            }
            return text.length() > 0 ? text.toString() : null;
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                Map<String, Object> text = textPart(textPart);
                if (text != null) {
                    content.add(text);
                }
            } else if (part instanceof MediaPart mediaPart) {
                content.add(mediaPart(mediaPart));
            } else if (part instanceof ToolCallPart toolCall) {
                // A tool call is a member of the message rather than an entry of its content, so it
                // is held back and written beside the array.
                toolCalls.add(toolCall(toolCall));
            } else if (part instanceof RawPart raw) {
                // The part goes back out as it arrived: its members were read whole off the wire, so
                // the protocol spells them again untouched, where this part sits.
                content.add(raw.members());
            } else if (!(part instanceof ReasoningPart)) {
                throw unsupportedPart(part);
            }
        }
        // A turn that only calls tools has nothing to put here, and the member is left out rather than
        // sent empty — the same rule the text branch above keeps.
        return content.isEmpty() ? null : content;
    }

    /**
     * Writes the turn's reasoning as the one member this endpoint carries it in, beside the content
     * rather than inside it: this protocol has no reasoning entry of its own, and the member is what
     * a provider that requires its reasoning back expects to find.
     *
     * <p>
     * Reasoning that came from another endpoint travels with its own name in extras, so it goes out
     * as it arrived — the configured name covers what this endpoint's own answers carried.
     */
    private void writeReasoning(List<ContentPart> parts, Map<String, Object> entry) {
        if (config.getReasoningField().isBlank()) {
            return;
        }
        StringBuilder reasoning = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof ReasoningPart reasoningPart && reasoningPart.getText() != null) {
                reasoning.append(reasoningPart.getText());
            }
        }
        if (reasoning.length() > 0) {
            entry.put(config.getReasoningField(), reasoning.toString());
        }
    }

    /**
     * Whether the message takes the array form of content: it does as soon as it is not text alone,
     * because neither an image nor a replayed tool call can be spelled inside a string. A field this
     * module does not model forces it too — a string content cannot carry extras — but a reasoning
     * part does not: its member sits beside the content, not in it.
     */
    private static boolean arrayContent(List<ContentPart> parts) {
        return parts.stream().anyMatch(part -> part instanceof MediaPart
                || part instanceof ToolCallPart
                || part instanceof RawPart
                || (part instanceof TextPart && part.getExtras() != null && !part.getExtras().isEmpty()));
    }

    /**
     * The message's parts with its groups flattened into them: this protocol has no layer holding one
     * part inside another, so a group goes out as the parts it holds, in their place. The group is
     * shape, and the shape this protocol has not got leaves no node for the fields it carried.
     */
    private static List<ContentPart> flatten(List<ContentPart> parts) {
        List<ContentPart> flat = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof ListPart group) {
                flat.addAll(flatten(group.getSubParts()));
            } else {
                flat.add(part);
            }
        }
        return flat;
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
        if (mediaType != null && !mediaType.isEmpty() && !mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
            throw new SynapseException("unsupported media type for OpenAI: " + mediaType);
        }
        boolean typeStated = mediaType != null && !mediaType.isEmpty();
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
                : new Base64Reader("data:" + mediaType + ";base64,",
                        Objects.requireNonNull(part.getSource(), "a media part with no uri carries a source")));
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
        entry.put("content", textOf(result.getSubParts()));
        putIfSet(entry, "tool_call_id", result.getCallId());
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
        putIfSet(entry, "id", part.getCallId());
        entry.put("type", "function");
        Map<String, Object> function = new LinkedHashMap<>();
        putIfSet(function, "name", part.getName());
        putIfSet(function, "arguments", part.getArgumentsJson());
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

    /**
     * The tool choice as the member it goes out as, or {@code null} when the call states none or a
     * mode this protocol has no word for. The modes that constrain nothing in particular are this
     * protocol's bare strings; naming a tool takes the object form, which is the only shape that
     * carries a name.
     *
     * <p>
     * A mode with no counterpart here is left unsent, so the call goes on with the endpoint's own
     * default; a name beside a mode that names no tool, or a missing name for the mode that needs
     * one, is the caller's own contradiction and is refused. Provider fields of the object form ride
     * in through the options' extras — a {@code tool_choice.…} path merges over what is written here.
     */
    private @Nullable Object toolChoice(ChatOptions options) {
        String mode = options.getToolChoice();
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case ChatOptions.TOOL_CHOICE_AUTO:
            case ChatOptions.TOOL_CHOICE_NONE:
            case ChatOptions.TOOL_CHOICE_REQUIRED:
                if (options.getToolChoiceName() != null) {
                    throw new SynapseException("unsupported tool choice for OpenAI: mode '" + mode
                            + "' names no tool, so a tool name has nowhere to go");
                }
                return mode;
            case ChatOptions.TOOL_CHOICE_TOOL:
                if (options.getToolChoiceName() == null) {
                    throw new SynapseException("unsupported tool choice for OpenAI: mode '"
                            + ChatOptions.TOOL_CHOICE_TOOL + "' has to name a tool");
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", options.getToolChoiceName());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "function");
                entry.put("function", function);
                return entry;
            default:
                // A mode this protocol has no word for goes out as nothing, not as a refusal.
                return null;
        }
    }

    private Stream<Map<String, Object>> tools(List<Tool> requestTools) {
        return requestTools.stream().map(requestTool -> tool(requestTool.definition()));
    }

    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.getName());
        function.put("description", definition.getDescription());
        putIfSet(function, "parameters", definition.getInputSchema());
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
            JsonSchema schema = format.getSchema();
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

    private static void putIfSet(Map<String, Object> members, String name, @Nullable Object value) {
        if (value != null) {
            members.put(name, value);
        }
    }

    /** Whether the extras, if any, name this member whole — the one case where the member's own value stands. */
    private static boolean namesWhole(@Nullable ProviderExtras extras, String member) {
        return extras != null && extras.contains(member);
    }

    /**
     * The text a tool result's parts say, as the one string this protocol takes there. A group of
     * parts goes out as the parts it holds — this protocol has no layer to hold one part inside
     * another, so the group is shape rather than a node of its own.
     */
    private String textOf(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : flatten(parts)) {
            if (!(part instanceof TextPart textPart)) {
                throw unsupportedPart(part);
            }
            if (textPart.getExtras() != null && !textPart.getExtras().isEmpty()) {
                throw new SynapseException(
                        "unsupported part for OpenAI: a tool result's content is a string, which cannot carry extras");
            }
            // A part with no text says nothing; appending it would spell the content "null".
            if (textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        return text.toString();
    }

    private SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI: " + part.getClass().getSimpleName());
    }

}
