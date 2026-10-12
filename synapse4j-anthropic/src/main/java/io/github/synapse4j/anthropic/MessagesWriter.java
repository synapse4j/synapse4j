package io.github.synapse4j.anthropic;

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
import io.github.synapse4j.data.ChatRole;
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
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSource;
import io.github.synapse4j.json.JsonWriter;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;
import io.github.synapse4j.util.Base64Reader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Writes the shared chat model as the Messages wire document. Stateless; one instance writes one
 * exchange, and it holds only what every document is written with — the application's codec, for
 * embedding schema strings and tool inputs as parsed JSON. The document being written stays an
 * argument of each call: it is the thing that varies.
 *
 * <p>
 * A request is assembled as the object it goes out as — one map per node, the members this module
 * models written into it with the node's extras merged over them — and written in one pass. The
 * conversation and the tools are the members that grow with the exchange, so they are not gathered
 * into lists here: their entries are produced as the writer walks them, one at a time. Every
 * name is a literal spelled out here, so whichever JSON library the application chose, the bytes
 * that leave are exactly this protocol's spelling, and a member whose value is not set is never
 * emitted.
 *
 * <p>
 * The conversation is stateless on the wire: the system message goes out as the top-level
 * {@code system} field on every request — the protocol keeps no server-side state, so the framing
 * travels with each call — while the history and the pending messages both become entries of
 * {@code messages}, in that order.
 *
 * <p>
 * A message's content takes the array-of-blocks form as soon as it is not text alone: an image, a
 * tool call, a tool result, the model's reasoning, or a text piece carrying a field of its own all
 * have a block shape this protocol spells. A tool result answers inside a {@code user} message —
 * the protocol has no tool role — and its parts stay blocks, so a result that carries extras keeps
 * them. The model's reasoning is replayed as the {@code thinking} block it arrived as, signature
 * included, because the endpoint refuses a continuation whose thinking was lost or rewritten. A
 * group of parts has no block of its own here, so it goes out as the parts it holds.
 *
 * <p>
 * What the protocol cannot express fails here rather than reaching the provider half expressed: a
 * part type with no block shape, media that is not an image, a response format it has no member
 * for — and, because {@code max_tokens} is required with no server-side default, a call that
 * states no limit at all. A tool-choice mode outside the set the protocol fixes is not one of
 * them: it is left unsent and the call goes on, as {@link #toolChoice} says.
 *
 * <p>
 * The {@link JsonWriter} is handed in, and where its bytes go is the caller's affair. This class
 * knows the document and nothing about the exchange that carries it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class MessagesWriter {

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
        ChatOptions options = request.getOptions();
        // Checked before anything is assembled: this endpoint has no server-side default for the
        // limit, so a call with no opinion on it cannot be asked at all. Refusing here names the
        // missing member, where inventing a number would send a limit the caller never chose — the
        // caller (or a request customizer) states it, or sets it once in the options' extras.
        requireMaxTokens(options);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("model", options.getModel());
        putIfSet(document, "tool_choice", toolChoice(options));
        putIfSet(document, "temperature", options.getTemperature());
        putIfSet(document, "max_tokens", options.getMaxOutputTokens());
        putIfSet(document, "top_p", options.getTopP());
        if (stream) {
            document.put("stream", true);
        }
        // The reasoning level and the answer's shape are one protocol object, so they are written into
        // one map — two members of the document would leave only the last one standing.
        Map<String, Object> outputConfig = outputConfig(request);
        if (!outputConfig.isEmpty()) {
            document.put("output_config", outputConfig);
        }
        // The extras of the request itself merge into the document's own members, so a path lands as
        // a member of this object rather than a level below it. They merge before the members that
        // carry extras of their own — the framing, the conversation, the tools: the reach of an entry
        // ends where it meets one of those, so each goes in after the merge, unless the entry names it
        // whole and stands in its place.
        ProviderExtras extras = options.getExtras();
        extras.mergeInto(document);
        ChatMessage system = request.getSystemMessage();
        if (system != null && !extras.contains("system")) {
            // The framing this protocol takes as a field of its own, outside the turn sequence and
            // resent with every call.
            document.put("system", system(system));
        }
        if (!extras.contains("messages")) {
            // Left as a source: the entries are produced as the writer walks the array rather than
            // gathered into a list first, so a conversation longer than memory still goes out.
            document.put("messages", JsonSource.of(() -> messages(request)));
        }
        if (!request.getTools().isEmpty() && !extras.contains("tools")) {
            document.put("tools", JsonSource.of(() -> tools(request.getTools())));
        }
        // The answer's shape carries extras of its own, so the request's reach ends at it: it is
        // written again after the merge, unless the request names it whole.
        if (outputConfig.containsKey("format") && !extras.contains("output_config", "format")) {
            outputConfig.put("format", responseFormat(options.getResponseFormat()));
        }
        return document;
    }

    /**
     * The token limit this endpoint requires. The shared model spells a limit as an opinion —
     * {@code null} meaning "no opinion" — and every other member can be left to the provider's
     * default, but this protocol has none: the wire always needs a number. So the opinion is
     * required here, and the options' extras are the documented way to state it once for every call
     * rather than repeating it per request.
     */
    private static void requireMaxTokens(ChatOptions options) {
        if (options.getMaxOutputTokens() == null && !options.getExtras().contains("max_tokens")) {
            throw new SynapseException("max_tokens is required by the Anthropic Messages API: set"
                    + " options.maxOutputTokens, or options.extras member 'max_tokens'");
        }
    }

    /**
     * The system message as the one field this protocol frames the model with. A plain text message
     * goes out as the string form; text pieces carrying fields of their own — a cache breakpoint
     * above all — take the array form, which is the only one that can hold them. The message's own
     * extras have no object to merge into here, since the field's value is the text rather than
     * a message object, and a part with nowhere to put a field fails rather than losing it.
     */
    private static Object system(ChatMessage system) {
        ProviderExtras messageExtras = system.getExtras();
        if (messageExtras != null && !messageExtras.isEmpty()) {
            throw new SynapseException("unsupported system message for Anthropic Messages:"
                    + " the system field is the text itself, which cannot carry message extras;"
                    + " put them on the text part");
        }
        List<TextPart> textParts = new ArrayList<>();
        boolean arrayForm = false;
        for (ContentPart part : flatten(system.getParts())) {
            if (!(part instanceof TextPart textPart)) {
                throw new SynapseException(
                        "unsupported part type for Anthropic Messages: " + part.getClass().getSimpleName());
            }
            textParts.add(textPart);
            ProviderExtras partExtras = textPart.getExtras();
            if (partExtras != null && !partExtras.isEmpty()) {
                arrayForm = true;
            }
        }
        if (arrayForm) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (TextPart textPart : textParts) {
                Map<String, Object> block = textBlock(textPart);
                if (block != null) {
                    blocks.add(block);
                }
            }
            return blocks;
        }
        StringBuilder text = new StringBuilder();
        for (TextPart textPart : textParts) {
            if (textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        return text.toString();
    }

    /**
     * The conversation as the messages it goes out as, in the order the caller built them. The
     * entries are produced as the writer walks them rather than gathered into a list first. The
     * system message is not among them — this protocol keeps no turn for it, and the framing travels
     * as its own field on every call.
     */
    private Stream<Map<String, Object>> messages(ChatRequest request) {
        return Stream.concat(request.getHistoryMessages().stream(), request.getPendingMessages().stream())
                .map(this::message);
    }

    private Map<String, Object> message(ChatMessage message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfSet(entry, "role", wireRole(message.getRole()));
        Object content = content(flatten(message.getParts()));
        // The content carries extras of its own, so the message's extras' reach ends here: it goes in
        // after the merge, unless the message names it whole.
        ProviderExtras messageExtras = message.getExtras();
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        if (content != null && !namesWhole(messageExtras, "content")) {
            entry.put("content", content);
        }
        return entry;
    }

    /**
     * The message's content as this protocol spells it — the array of blocks, or the string the text
     * parts join into — or {@code null} when it carries none.
     */
    private @Nullable Object content(List<ContentPart> parts) {
        if (arrayContent(parts)) {
            return blocks(parts);
        }
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            String value = ((TextPart) part).getText();
            if (value != null) {
                text.append(value);
            }
        }
        return text.length() > 0 ? text.toString() : null;
    }

    /**
     * The role as this protocol spells it. This protocol has two conversation roles, so the
     * application's own messages — the developer role — go out as the user's; a role the library
     * does not define is written as it stands, the protocol's own vocabulary being the caller's to
     * spell.
     */
    private static @Nullable String wireRole(@Nullable String role) {
        return switch (ChatRole.from(role)) {
            case USER, ASSISTANT -> role;
            case DEVELOPER -> ChatRole.USER.value();
            case null -> role;
        };
    }

    /**
     * Whether the message takes the array form of content: it does as soon as it is not text alone,
     * because an image, a tool call, a tool result and the model's reasoning each have a block
     * shape a string cannot hold. A field this module does not model forces it too — a string
     * content cannot carry extras.
     */
    private static boolean arrayContent(List<ContentPart> parts) {
        return parts.stream()
                .anyMatch(part -> !(part instanceof TextPart)
                        || (part.getExtras() != null && !part.getExtras().isEmpty()));
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

    /** One message's parts as the content blocks they become, in the order they were added. */
    private List<Map<String, Object>> blocks(List<ContentPart> parts) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                Map<String, Object> block = textBlock(textPart);
                if (block != null) {
                    blocks.add(block);
                }
            } else if (part instanceof MediaPart mediaPart) {
                blocks.add(imageBlock(mediaPart));
            } else if (part instanceof ToolCallPart toolCall) {
                blocks.add(toolUse(toolCall));
            } else if (part instanceof ToolResultPart toolResult) {
                blocks.add(toolResult(toolResult));
            } else if (part instanceof ReasoningPart reasoning) {
                blocks.add(thinking(reasoning));
            } else if (part instanceof RawPart raw) {
                // The block goes back out as it arrived: its members were read whole off the wire,
                // so the protocol spells them again untouched, in the place this part holds.
                blocks.add(raw.members());
            } else {
                throw unsupportedPart(part);
            }
        }
        return blocks;
    }

    /**
     * A text part as the block it becomes, or {@code null} when it carries neither text nor extras —
     * a part with nothing to say, which the protocol has no place for.
     */
    private static @Nullable Map<String, Object> textBlock(TextPart part) {
        boolean hasText = part.getText() != null && !part.getText().isEmpty();
        ProviderExtras partExtras = part.getExtras();
        if (!hasText && (partExtras == null || partExtras.isEmpty())) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        if (hasText) {
            block.put("text", part.getText());
        }
        if (partExtras != null) {
            partExtras.mergeInto(block);
        }
        return block;
    }

    /**
     * A media part in the protocol's image shape. A URL is passed through as it stands: the provider
     * fetches it, and the payload's type is then the provider's to discover. A payload is inlined as
     * base64 instead, which is where the type has to be spelled out — the source object is the only
     * thing that declares it. The bytes are handed to the writer through a {@link Base64Reader}, so
     * a payload larger than memory still goes out.
     */
    private static Map<String, Object> imageBlock(MediaPart part) {
        String mediaType = part.getMediaType();
        if (mediaType != null && !mediaType.isEmpty() && !mediaType.startsWith(IMAGE_TYPE_PREFIX)) {
            throw new SynapseException("unsupported media type for Anthropic Messages: " + mediaType);
        }
        if (part.getUri() == null && part.getSource() == null) {
            throw new SynapseException(
                    "unsupported media part for Anthropic Messages: neither uri nor source is set");
        }
        Map<String, Object> source = new LinkedHashMap<>();
        if (part.getUri() != null) {
            source.put("type", "url");
            source.put("url", part.getUri());
        } else {
            if (mediaType == null || mediaType.isEmpty()) {
                throw new SynapseException(
                        "unsupported media part for Anthropic Messages: mediaType is required to inline the payload");
            }
            source.put("type", "base64");
            source.put("media_type", mediaType);
            source.put("data", new Base64Reader(Objects.requireNonNull(part.getSource(),
                    "a media part with no uri carries a source")));
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(block);
        }
        return block;
    }

    /**
     * A tool call as the {@code tool_use} block it becomes. The arguments are JSON text in the
     * shared model and an object on this wire, so the text is parsed here — the same trip a tool
     * schema takes — and a call that carries no argument text spells the empty input: that is what
     * a model's no-argument call means, and the member itself takes an object rather than text.
     */
    private Map<String, Object> toolUse(ToolCallPart part) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        putIfSet(block, "id", part.getCallId());
        putIfSet(block, "name", part.getName());
        block.put("input", input(part.getArgumentsJson()));
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(block);
        }
        return block;
    }

    /** The tool input as the object it goes out as; nothing said spells the empty object. */
    private Map<String, Object> input(@Nullable String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = codec.decode(argumentsJson, Map.class);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            throw new SynapseException("tool input is not a JSON object", e);
        }
    }

    /**
     * A tool result as the {@code tool_result} block it becomes. The result's own content becomes
     * the block's {@code content}: text alone goes as the string form, anything else — a piece
     * carrying a field of its own, an image the tool answered with — takes the block array, which
     * is the only form that can hold it.
     */
    private static Map<String, Object> toolResult(ToolResultPart result) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        putIfSet(block, "tool_use_id", result.getCallId());
        Object content = resultContent(result.getSubParts());
        if (result.isError()) {
            // Only a failure is stated: false is the protocol's own default, and emitting it would
            // be a value the caller never chose.
            block.put("is_error", true);
        }
        // The content carries extras of its own when it takes the block form, so the result's extras'
        // reach ends here: it goes in after the merge, unless the result names it whole.
        ProviderExtras resultExtras = result.getExtras();
        if (resultExtras != null) {
            resultExtras.mergeInto(block);
        }
        if (!namesWhole(resultExtras, "content")) {
            block.put("content", content);
        }
        return block;
    }

    /** What a tool answered: the text itself when the result is plain text, its blocks otherwise. */
    private static Object resultContent(List<ContentPart> parts) {
        List<ContentPart> flat = flatten(parts);
        boolean plainText = flat.stream().allMatch(part -> part instanceof TextPart textPart
                && (textPart.getExtras() == null || textPart.getExtras().isEmpty()));
        if (plainText) {
            StringBuilder text = new StringBuilder();
            for (ContentPart part : flat) {
                String value = ((TextPart) part).getText();
                if (value != null) {
                    text.append(value);
                }
            }
            return text.toString();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ContentPart part : flat) {
            if (part instanceof TextPart textPart) {
                Map<String, Object> block = textBlock(textPart);
                if (block != null) {
                    blocks.add(block);
                }
            } else if (part instanceof MediaPart mediaPart) {
                blocks.add(imageBlock(mediaPart));
            } else {
                throw unsupportedPart(part);
            }
        }
        return blocks;
    }

    /**
     * The model's reasoning as the {@code thinking} block it arrived as. The opaque companion the
     * provider attached — the signature that proves the block was the model's — rides in the part's
     * extras under the name the protocol spells it with, so it merges over the block and goes back
     * exactly where it came from. An instance that names another block type (the safety-redacted
     * thinking this module reads into the same part) overrides the type written here, which is how
     * that block is replayed as itself.
     */
    private static Map<String, Object> thinking(ReasoningPart part) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "thinking");
        if (part.getText() != null) {
            block.put("thinking", part.getText());
        }
        ProviderExtras partExtras = part.getExtras();
        if (partExtras != null) {
            partExtras.mergeInto(block);
        }
        return block;
    }

    /**
     * The tool choice as the member it goes out as, or {@code null} when the call states none or a
     * mode this protocol has no word for. This protocol always takes the object form, and its four
     * shapes are the set the specification fixes: the shared vocabulary's {@code auto}, {@code none}
     * and {@code tool} map onto them, and {@code required}, which this protocol spells {@code any},
     * is the translation. A mode with no counterpart here is left unsent, so the call goes on with
     * the endpoint's own default; a name beside a mode that names no tool, or a missing name for the
     * mode that needs one, is the caller's own contradiction and is refused. Provider fields of the
     * object form ride in through the options' extras — a {@code tool_choice.…} path merges over what is
     * written here.
     */
    private static @Nullable Object toolChoice(ChatOptions options) {
        String mode = options.getToolChoice();
        if (mode == null) {
            return null;
        }
        String name = options.getToolChoiceName();
        switch (mode) {
            case ChatOptions.TOOL_CHOICE_AUTO:
            case ChatOptions.TOOL_CHOICE_NONE:
            case ChatOptions.TOOL_CHOICE_REQUIRED:
            case "any":
                if (name != null) {
                    throw new SynapseException("unsupported tool choice for Anthropic Messages: mode '" + mode
                            + "' names no tool, so a tool name has nowhere to go");
                }
                Map<String, Object> choice = new LinkedHashMap<>();
                // The translation lives here: the shared vocabulary says "at least one", this
                // protocol spells that "any".
                choice.put("type",
                        ChatOptions.TOOL_CHOICE_REQUIRED.equals(mode) ? "any" : mode);
                return choice;
            case ChatOptions.TOOL_CHOICE_TOOL:
                if (name == null) {
                    throw new SynapseException("unsupported tool choice for Anthropic Messages: mode '"
                            + ChatOptions.TOOL_CHOICE_TOOL + "' has to name a tool");
                }
                Map<String, Object> named = new LinkedHashMap<>();
                named.put("type", "tool");
                named.put("name", name);
                return named;
            default:
                // A mode this protocol has no word for goes out as nothing, not as a refusal: the
                // call proceeds on the endpoint's own default, which is all a protocol without the
                // mode can offer.
                return null;
        }
    }

    /**
     * The reasoning level and the requested answer shape as the one {@code output_config} object
     * this protocol carries both in. The level is written as it stands — no protocol fixes the set
     * of levels, so a level of the endpoint's own is the endpoint's to judge — and the shape is
     * only written when the protocol has a member for it.
     */
    private Map<String, Object> outputConfig(ChatRequest request) {
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        ChatOptions options = request.getOptions();
        if (options.getReasoningEffort() != null) {
            outputConfig.put("effort", options.getReasoningEffort());
        }
        Map<String, Object> format = responseFormat(options.getResponseFormat());
        if (!format.isEmpty()) {
            outputConfig.put("format", format);
        }
        return outputConfig;
    }

    /**
     * The response format as the object it goes out as, or an empty map when nothing goes out.
     * This protocol expresses exactly one shape — a JSON Schema the endpoint then enforces — so a
     * schema-shaped request is translated, prose is what the endpoint answers with when no format
     * is asked for (its own members still go out as the caller spelled them), and any other stated
     * shape is refused rather than dropped: prose where JSON was asked for would look like success.
     * The requirement's properties this protocol has no member for — a schema name or description,
     * and the enforcement flag, which this protocol cannot switch off — are left unsent, so the call
     * goes on with the schema it can honour.
     */
    private Map<String, Object> responseFormat(ChatResponseFormat format) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String type = format.getType();
        if (ChatResponseFormat.TYPE_JSON_SCHEMA.equals(type)) {
            entry.put("type", "json_schema");
            putIfSet(entry, "schema", format.getSchema());
        } else if (type != null && !ChatResponseFormat.TYPE_TEXT.equals(type)) {
            throw new SynapseException(
                    "unsupported response format type for Anthropic Messages: " + type);
        }
        format.getExtras().mergeInto(entry);
        return entry;
    }

    private Stream<Map<String, Object>> tools(List<Tool> requestTools) {
        return requestTools.stream().map(requestTool -> tool(requestTool.definition()));
    }

    /**
     * A tool as the object it goes out as: the custom-tool shape this protocol takes, whose schema
     * rides under {@code input_schema} and whose enforcement flag is a top-level member beside it —
     * a set flag is translated as it stands, and an unset one stays off the wire, the protocol's own
     * default being not to constrain.
     */
    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> tool = new LinkedHashMap<>();
        putIfSet(tool, "name", definition.getName());
        putIfSet(tool, "description", definition.getDescription());
        putIfSet(tool, "input_schema", definition.getInputSchema());
        putIfSet(tool, "strict", definition.getStrict());
        definition.getExtras().mergeInto(tool);
        return tool;
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

    private static SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for Anthropic Messages: " + part.getClass().getSimpleName());
    }

}
