package io.github.synapse4j.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.MediaPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.ReasoningPart;
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
 * Writes the shared chat model as the Responses wire document. Stateless; one instance writes one
 * exchange, and it holds what the whole exchange writes by — the application's codec, for embedding
 * schema strings as parsed maps. The document being written is the opposite case and stays an
 * argument of each call: it is the thing that varies.
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
 * This protocol takes the conversation as one flat array of items rather than as messages carrying
 * everything, so a message is written as what it says and the parts this protocol hoists out of it —
 * a tool call, a tool result, the model's reasoning — are written as items of their own, in the order
 * they were added. That is also what lets each of those items carry the identity the response gave
 * it: a field this module does not model rides in the part's extras and comes back to the same place.
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
class ResponsesWriter {

    /** The type prefix of the media this module renders; the protocol's image shape takes nothing else. */
    private static final String IMAGE_TYPE_PREFIX = "image/";

    /**
     * The option-bag name of the response the server already holds, when a call chains onto it.
     * The client writes it from an answer in {@code continueWith}; this class reads it back to
     * decide how much of the conversation still has to go out, so it lives here under one name
     * rather than being spelled twice.
     */
    static final String PREVIOUS_RESPONSE_ID = "previous_response_id";

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
     * with a member of the request document rather than with another endpoint or content type.
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
        ChatMessage system = request.getSystemMessage();
        if (system != null) {
            // The framing this protocol takes as one string at the top, outside the turn sequence.
            document.put("instructions", instructions(system));
        }
        document.put("input", input(request));
        if (!request.getTools().isEmpty()) {
            document.put("tools", tools(request.getTools()));
        }
        putIfSet(document, "tool_choice", toolChoice(request.getOptions()));
        putIfSet(document, "temperature", request.getOptions().getTemperature());
        // The token limit goes out under the one name this protocol fixed for it, unlike the
        // chat-completions endpoint, whose servers disagree about the spelling.
        putIfSet(document, "max_output_tokens", request.getOptions().getMaxOutputTokens());
        putIfSet(document, "top_p", request.getOptions().getTopP());
        if (request.getOptions().getReasoningEffort() != null) {
            // The level is nested one object deep here rather than sent as a member of its own.
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("effort", request.getOptions().getReasoningEffort());
            document.put("reasoning", reasoning);
        }
        if (request.getResponseFormat().getType() != null
                || !request.getResponseFormat().getExtras().isEmpty()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("format", responseFormat(request.getResponseFormat()));
            document.put("text", text);
        }
        if (stream) {
            document.put("stream", true);
        }
        // The extras of the request itself merge into the document's own members, so a path lands as
        // a member of this object rather than a level below it. Merged last, so a path set on both
        // sides is the caller's value that goes out.
        request.getOptions().getExtras().mergeInto(document);
        return document;
    }

    /**
     * The conversation as the array of items it goes out as. A message contributes the item holding
     * what it says, and then one item for each part this protocol carries beside a message rather
     * than inside it — so a turn that called a tool is replayed as the message it was and the call it
     * made, in the order the caller built them.
     *
     * <p>
     * How much of the conversation that is depends on where the server already stands. With a chain
     * anchor — the application sets {@code previous_response_id} in the options bag, and it merges
     * into the document from there as it always has — the server holds the history itself, so only
     * the pending messages go out. Without one the server holds nothing, and the whole conversation
     * goes: the history first, then the pending.
     */
    private List<Map<String, Object>> input(ChatRequest request) {
        List<Map<String, Object>> input = new ArrayList<>();
        if (!request.getOptions().getExtras().contains(PREVIOUS_RESPONSE_ID)) {
            for (ChatMessage message : request.getHistoryMessages()) {
                addMessage(input, message);
            }
        }
        for (ChatMessage message : request.getPendingMessages()) {
            addMessage(input, message);
        }
        return input;
    }

    /**
     * The system message's text as the one string this field takes. The field carries nothing
     * beside that text, so a part this protocol cannot spell there — or a field riding beside the
     * text — fails here rather than reaching the provider half expressed.
     */
    private static String instructions(ChatMessage system) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : system.getParts()) {
            if (!(part instanceof TextPart textPart)) {
                throw unsupportedPart(part);
            }
            if (textPart.getExtras() != null && !textPart.getExtras().isEmpty()) {
                throw new SynapseException("unsupported part for OpenAI Responses:"
                        + " instructions are a string, which cannot carry extras");
            }
            if (textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        if (system.getExtras() != null && !system.getExtras().isEmpty()) {
            throw new SynapseException("unsupported message for OpenAI Responses:"
                    + " instructions are a string, which cannot carry extras");
        }
        return text.toString();
    }

    private void addMessage(List<Map<String, Object>> input, ChatMessage message) {
        List<ContentPart> content = new ArrayList<>();
        for (ContentPart part : message.getParts()) {
            if (part instanceof TextPart || part instanceof MediaPart) {
                content.add(part);
            }
        }
        // A field set on the message belongs to the item the message becomes. A message that becomes
        // no item of its own — one that carries nothing but the parts this protocol hoists out of it,
        // which is what a turn of tool results is — has nowhere else for them to go, so the items it
        // does become carry them instead.
        ProviderExtras messageExtras = content.isEmpty() ? message.getExtras() : null;
        if (!content.isEmpty()) {
            input.add(messageItem(message, content));
        }
        for (ContentPart part : message.getParts()) {
            if (part instanceof ToolCallPart call) {
                input.add(functionCall(call, messageExtras));
            } else if (part instanceof ToolResultPart result) {
                input.add(functionCallOutput(result, messageExtras));
            } else if (part instanceof ReasoningPart reasoning) {
                input.add(reasoningItem(reasoning, messageExtras));
            } else if (!(part instanceof TextPart) && !(part instanceof MediaPart)) {
                throw unsupportedPart(part);
            }
        }
    }

    /**
     * What a message says as the item it becomes. A message that is text alone takes the plain-string
     * content form; anything else — an image, a part carrying a field this module does not model —
     * takes the array form, which is the only one that can hold it.
     */
    private static Map<String, Object> messageItem(ChatMessage message, List<ContentPart> content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", message.getRole());
        if (arrayContent(content)) {
            // What the assistant said is this protocol's own kind of content entry, and what anyone
            // else says is the input kind: an assistant turn replayed as an item has to look like the
            // item it was, or the endpoint reads it as something the model did not say.
            String textType = ChatRole.ASSISTANT.equals(message.getRole()) ? "output_text" : "input_text";
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ContentPart part : content) {
                if (part instanceof TextPart textPart) {
                    Map<String, Object> text = textEntry(textPart, textType);
                    if (text != null) {
                        entries.add(text);
                    }
                } else {
                    entries.add(mediaEntry((MediaPart) part));
                }
            }
            entry.put("content", entries);
        } else {
            StringBuilder text = new StringBuilder();
            for (ContentPart part : content) {
                String value = ((TextPart) part).getText();
                if (value != null) {
                    text.append(value);
                }
            }
            if (text.length() > 0) {
                entry.put("content", text.toString());
            }
        }
        ProviderExtras messageExtras = message.getExtras();
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * Whether the message takes the array form of content: it does as soon as it is not text alone,
     * because an image cannot be spelled inside a string, and neither can a field this module does
     * not model.
     */
    private static boolean arrayContent(List<ContentPart> content) {
        return content.stream().anyMatch(part -> part instanceof MediaPart
                || (part instanceof TextPart && part.getExtras() != null && !part.getExtras().isEmpty()));
    }

    /**
     * A text part as the entry it becomes, or {@code null} when it carries neither text nor extras —
     * a part with nothing to say, which the protocol has no place for.
     *
     * @param part the part to render
     * @param type the entry type this position takes: {@code input_text} inside a message the caller
     *                 wrote, {@code output_text} inside one the model said
     */
    private static @Nullable Map<String, Object> textEntry(TextPart part, String type) {
        boolean hasText = part.getText() != null && !part.getText().isEmpty();
        if (!hasText && (part.getExtras() == null || part.getExtras().isEmpty())) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
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
    private static Map<String, Object> mediaEntry(MediaPart part) {
        String mediaType = part.getMediaType();
        if (mediaType != null && !mediaType.isEmpty() && !mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
            throw new SynapseException("unsupported media type for OpenAI Responses: " + mediaType);
        }
        boolean typeStated = mediaType != null && !mediaType.isEmpty();
        if (part.getUri() == null && part.getSource() == null) {
            throw new SynapseException(
                    "unsupported media part for OpenAI Responses: neither uri nor source is set");
        }
        if (part.getUri() == null && !typeStated) {
            throw new SynapseException(
                    "unsupported media part for OpenAI Responses: mediaType is required to inline the payload");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "input_image");
        entry.put("image_url", part.getUri() != null ? part.getUri()
                : new Base64Reader("data:" + mediaType + ";base64,",
                        Objects.requireNonNull(part.getSource(), "a media part with no uri carries a source")));
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * A tool call as the item it becomes. The call is not nested inside the message that produced it
     * in this protocol, and its own identity — the id and the status the response gave it — rides in
     * the part's extras, which is how a replayed call comes back as the item it was.
     */
    private static Map<String, Object> functionCall(ToolCallPart part, @Nullable ProviderExtras messageExtras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "function_call");
        entry.put("call_id", part.getCallId());
        entry.put("name", part.getName());
        entry.put("arguments", part.getArgumentsJson());
        // The message's extras first, the part's own after: the more specific node is merged last,
        // so it wins where both set the same path.
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * A tool result as the item it becomes. The result is an item of its own here, so it carries no
     * role: the call it answers is what says where it belongs.
     */
    private static Map<String, Object> functionCallOutput(ToolResultPart result,
            @Nullable ProviderExtras messageExtras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "function_call_output");
        entry.put("call_id", result.getCallId());
        entry.put("output", output(result.getParts()));
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras resultExtras = result.getExtras();
        if (resultExtras != null) {
            resultExtras.mergeInto(entry);
        }
        return entry;
    }

    /**
     * What a tool answered, as the protocol takes it: the text itself when the result is text alone,
     * and an array of content entries otherwise — a result that carried an image has nowhere to go
     * inside a string.
     */
    private static Object output(List<ContentPart> parts) {
        if (parts.stream().allMatch(TextPart.class::isInstance)) {
            return textOf(parts);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                // A tool's answer is input the caller's side produced, not something the model said.
                Map<String, Object> text = textEntry(textPart, "input_text");
                if (text != null) {
                    content.add(text);
                }
            } else if (part instanceof MediaPart mediaPart) {
                content.add(mediaEntry(mediaPart));
            } else {
                throw unsupportedPart(part);
            }
        }
        return content;
    }

    /**
     * The reasoning as the item it becomes: this protocol keeps the summary beside the answer rather
     * than inside it, and carries the opaque companion that proves the reasoning came from the model
     * — both of which have to go back on the next turn for the model to accept its own turn.
     */
    private static Map<String, Object> reasoningItem(ReasoningPart part, @Nullable ProviderExtras messageExtras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "reasoning");
        if (part.getText() != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", "summary_text");
            summary.put("text", part.getText());
            entry.put("summary", List.of(summary));
        }
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(entry);
        }
        return entry;
    }

    private static String textOf(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (!(part instanceof TextPart textPart)) {
                throw unsupportedPart(part);
            }
            if (textPart.getExtras() != null && !textPart.getExtras().isEmpty()) {
                throw new SynapseException("unsupported part for OpenAI Responses:"
                        + " a tool result's content is a string, which cannot carry extras");
            }
            if (textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        return text.toString();
    }

    private List<Map<String, Object>> tools(List<Tool> requestTools) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Tool requestTool : requestTools) {
            tools.add(tool(requestTool.definition()));
        }
        return tools;
    }

    /**
     * A tool as the object it goes out as. Unlike chat completions, this protocol puts the function's
     * own fields at the top level of the tool object rather than in a {@code function} object.
     */
    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", definition.getName());
        putIfSet(tool, "description", definition.getDescription());
        putIfSet(tool, "parameters", parseSchema(definition.getInputSchema()));
        putIfSet(tool, "strict", definition.getStrict());
        definition.getExtras().mergeInto(tool);
        return tool;
    }

    /**
     * The tool choice as the member it goes out as, or {@code null} when the call states none. The
     * modes that constrain nothing in particular are this protocol's bare strings; naming a tool
     * takes the object form, which is the only shape that carries a name.
     *
     * <p>
     * Every mode the call states is translated or refused here, and the name with it: a knob that
     * quietly did nothing would read from above as a model that ignored its instructions. Provider
     * fields of the object form ride in through the options bag — a {@code tool_choice.…} path merges
     * over what is written here.
     */
    private static @Nullable Object toolChoice(ChatOptions options) {
        String mode = options.getToolChoice();
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case ChatOptions.TOOL_CHOICE_AUTO:
            case ChatOptions.TOOL_CHOICE_NONE:
            case ChatOptions.TOOL_CHOICE_REQUIRED:
                if (options.getToolChoiceName() != null) {
                    throw new SynapseException("unsupported tool choice for OpenAI Responses: mode '" + mode
                            + "' names no tool, so a tool name has nowhere to go");
                }
                return mode;
            case ChatOptions.TOOL_CHOICE_TOOL:
                if (options.getToolChoiceName() == null) {
                    throw new SynapseException("unsupported tool choice for OpenAI Responses: mode '"
                            + ChatOptions.TOOL_CHOICE_TOOL + "' has to name a tool");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "function");
                entry.put("name", options.getToolChoiceName());
                return entry;
            default:
                throw new SynapseException("unsupported tool choice mode for OpenAI Responses: " + mode);
        }
    }

    /**
     * The response format as the object it goes out as; the caller only asks for one when it states
     * something. This protocol nests it under the request's {@code text} object.
     */
    private Map<String, Object> responseFormat(ChatResponseFormat format) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(format.getType())) {
            entry.put("type", "json_schema");
            entry.put("name", format.getName() != null ? format.getName() : "response");
            putIfSet(entry, "description", format.getDescription());
            putIfSet(entry, "strict", format.getStrict());
            Map<String, Object> schema = parseSchema(format.getSchema());
            if (schema != null) {
                entry.put("schema", schema);
            }
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

    private static SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI Responses: " + part.getClass().getSimpleName());
    }

}
