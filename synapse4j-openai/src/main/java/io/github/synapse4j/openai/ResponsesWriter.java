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
 * Writes the shared chat model as the Responses wire document. Stateless; one instance writes one
 * exchange, and it holds what the whole exchange writes by — the endpoint's conventions, for the
 * members whose name varies between servers. The document being written is the opposite case and
 * stays an argument of each call: it is the thing that varies.
 *
 * <p>
 * A request is assembled as the object it goes out as — one map per node, the members this module
 * models written into it with the node's extras merged over them — and written in one pass. The
 * conversation and the tools are the members that grow with the exchange, so they are not gathered
 * into lists here: their entries are produced as the writer walks them, one at a time. The maps
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
     * The name the options' extras carry the response under, when a call chains onto it.
     * The client writes it from an answer in {@code continueWith}; this class reads it back to
     * decide how much of the conversation still has to go out, so it lives here under one name
     * rather than being spelled twice.
     */
    static final String PREVIOUS_RESPONSE_ID = "previous_response_id";

    /**
     * The wire name of the member that says whether the endpoint keeps the answer, read from the
     * family configuration here and from the request's extras by the client that folds answers
     * in — one name, spelled once for both sides.
     */
    static final String STORE = "store";

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
        if (stream) {
            document.put("stream", true);
        }
        // Null means no opinion: the member stays off the wire and the endpoint's own default —
        // keep — stands. Written before the extras merge, so an instance that sets store itself wins
        // with no special-casing here.
        putIfSet(document, STORE, config.getStoreResponses());
        // The extras of the request itself merge into the document's own members, so a path lands as
        // a member of this object rather than a level below it. They merge before the members that
        // carry extras of their own — the framing, the conversation, the tools, the response format:
        // the reach of an entry ends where it meets one of those, so each goes in after the merge,
        // unless the entry names it whole and stands in its place.
        ProviderExtras extras = request.getOptions().getExtras();
        extras.mergeInto(document);
        ChatMessage system = request.getSystemMessage();
        if (system != null && saysSomething(system) && !extras.contains("instructions")) {
            // The framing this protocol takes as one string at the top, outside the turn sequence.
            document.put("instructions", instructions(system));
        }
        if (!extras.contains("input")) {
            // Left as a source: the items are produced as the writer walks the array rather than
            // gathered into a list first, so a conversation longer than memory still goes out.
            document.put("input", JsonSource.of(() -> input(request)));
        }
        if (!request.getTools().isEmpty() && !extras.contains("tools")) {
            document.put("tools", JsonSource.of(() -> tools(request.getTools())));
        }
        ChatResponseFormat format = request.getOptions().getResponseFormat();
        if ((format.getType() != null || !format.getExtras().isEmpty()) && !extras.contains("text")) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("format", responseFormat(format));
            document.put("text", text);
        }
        return document;
    }

    /**
     * The conversation as the items it goes out as, in the order the caller built them. The items
     * are produced as the writer walks them rather than gathered into a list first.
     *
     * <p>
     * With a chain anchor — the application sets {@code previous_response_id} in the options' extras —
     * the server already holds the history, so only the pending messages go out. Without one the
     * whole conversation goes: the history first, then the pending.
     */
    private Stream<Map<String, Object>> input(ChatRequest request) {
        Stream<ChatMessage> history = request.getOptions().getExtras().contains(PREVIOUS_RESPONSE_ID)
                ? Stream.empty()
                : request.getHistoryMessages().stream();
        return Stream.concat(history, request.getPendingMessages().stream()).flatMap(this::items);
    }

    /**
     * The system message's text as the one string this field takes. The field carries nothing
     * beside that text, so a part this protocol cannot spell there — or a field riding beside the
     * text — fails here rather than reaching the provider half expressed. A group of parts has no
     * place of its own here either, so it goes out as the text it holds.
     */
    private static String instructions(ChatMessage system) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : flatten(system.getParts())) {
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

    /**
     * The message's parts with its groups flattened into them: this field is one string, so a group has
     * no node of its own and goes out as the text it holds.
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

    private Stream<Map<String, Object>> items(ChatMessage message) {
        if (message.getParts().isEmpty()) {
            // A message with nothing in it makes no item at all — unless it carries fields of its
            // own, which belong to it and have nowhere else to go, so it becomes the one item that
            // holds them.
            ProviderExtras own = message.getExtras();
            Map<String, Object> item = own == null ? null : messageItem(message.getRole(), List.of(), own);
            return item == null ? Stream.empty() : Stream.of(item);
        }
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
        boolean[] messageAdded = { false };
        return message.getParts().stream().flatMap(part -> {
            if (part instanceof ListPart item) {
                // The part holds one item whole — its content entries and its own members — so it
                // goes back out as that item, in the place it held.
                return Stream.of(messageItem(item, message.getRole(), messageExtras));
            } else if (part instanceof RawPart raw) {
                // A part kept whole at the top of a message is an item this module does not model:
                // its members are the item, so they go out as they were read, with the message's own
                // fields merged in beside them.
                Map<String, Object> item = new LinkedHashMap<>();
                if (messageExtras != null) {
                    messageExtras.mergeInto(item);
                }
                item.putAll(raw.members());
                return Stream.of(item);
            } else if (part instanceof ToolCallPart call) {
                return Stream.of(functionCall(call, messageExtras));
            } else if (part instanceof ToolResultPart result) {
                return Stream.of(functionCallOutput(result, messageExtras));
            } else if (part instanceof ReasoningPart reasoning) {
                Map<String, Object> item = reasoningItem(reasoning, messageExtras);
                return item == null ? Stream.empty() : Stream.of(item);
            } else if (part instanceof TextPart || part instanceof MediaPart) {
                // Text and images make no item of their own: they are the message's own item, which
                // stands where the first of them sits — so a part the model wrote before it answered,
                // its reasoning, keeps the place the turn gives it.
                if (messageAdded[0]) {
                    return Stream.empty();
                }
                messageAdded[0] = true;
                Map<String, Object> item = messageItem(message.getRole(), content, message.getExtras());
                return item == null ? Stream.empty() : Stream.of(item);
            } else {
                throw unsupportedPart(part);
            }
        });
    }

    private static @Nullable Map<String, Object> messageItem(@Nullable String role, List<ContentPart> content,
            @Nullable ProviderExtras extras) {
        Object contentValue = contentValue(role, content);
        if (contentValue == null && (extras == null || extras.isEmpty())) {
            // Nothing but the role is not a turn, and this protocol has no place for one.
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfSet(entry, "role", role);
        // The content carries extras of its own, so the extras' reach ends here: it goes in after the
        // merge, unless the extras name it whole.
        if (extras != null) {
            extras.mergeInto(entry);
        }
        if (!namesWhole(extras, "content")) {
            // Content is required of a message item, so a message whose parts say nothing still writes
            // it — the empty string — rather than going out without a member the protocol demands.
            entry.put("content", contentValue != null ? contentValue : "");
        }
        return entry;
    }

    /**
     * What a message says as the content it becomes. A message that is text alone takes the
     * plain-string content form; anything else — an image, a part carrying a field this module does
     * not model — takes the array form, which is the only one that can hold it. {@code null} when the
     * message carries nothing to put there. A role this protocol has no word for is refused whichever
     * form the content takes: the string form shapes nothing by it, but the role still has to be one
     * this protocol can read.
     */
    private static @Nullable Object contentValue(@Nullable String role, List<ContentPart> content) {
        if (!arrayContent(content)) {
            StringBuilder text = new StringBuilder();
            for (ContentPart part : content) {
                String value = ((TextPart) part).getText();
                if (value != null) {
                    text.append(value);
                }
            }
            if (text.length() == 0) {
                return null;
            }
            textType(role);
            return text.toString();
        }
        // What the assistant said is this protocol's own kind of content entry, and what anyone else
        // says is the input kind: an assistant turn replayed as an item has to look like the item it
        // was, or the endpoint reads it as something the model did not say.
        String textType = textType(role);
        return contentEntries(content, textType);
    }

    /**
     * The content-entry type a message's text takes under the given role: the model's own kind for an
     * assistant turn, the input kind for everyone else. A role this protocol has no word for is
     * refused — this protocol reads the role to shape its content, so it cannot leave one unread.
     */
    private static String textType(@Nullable String role) {
        return switch (ChatRole.from(role)) {
            case ASSISTANT -> "output_text";
            case USER, DEVELOPER -> "input_text";
            case null -> throw new SynapseException("unsupported role for OpenAI Responses: "
                    + (role == null ? "the role is unset" : role));
        };
    }

    /**
     * A group at the top of a message as the item it becomes: this protocol answers with a flat array
     * of items, and a {@code message} item is what a group stands for. Its content is written in the
     * array form whatever it holds, because that is the form its entries were read from and the only
     * one that can carry an entry's type and the fields beside it.
     */
    private static Map<String, Object> messageItem(ListPart part, @Nullable String role,
            @Nullable ProviderExtras messageExtras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfSet(entry, "role", role);
        String textType = textType(role);
        List<Map<String, Object>> content = contentEntries(part.getSubParts(), textType);
        // The content carries extras of its own, so the extras' reach ends here. The message's extras
        // are merged first, the part's own after — the more specific node last; the content goes in
        // after both, unless one of them names it whole.
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras extras = part.getExtras();
        if (extras != null) {
            extras.mergeInto(entry);
        }
        if (!namesWhole(messageExtras, "content") && !namesWhole(extras, "content")) {
            entry.put("content", content);
        }
        return entry;
    }

    /**
     * The content entries a group of parts becomes, in the order the parts sit: a text part as the
     * entry this position takes, a part kept whole as its own members, an image as the protocol's
     * image shape. Anything else is not a content entry, and is refused rather than written half
     * expressed.
     */
    private static List<Map<String, Object>> contentEntries(List<ContentPart> parts, String textType) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                Map<String, Object> text = textEntry(textPart, textType);
                if (text != null) {
                    entries.add(text);
                }
            } else if (part instanceof RawPart raw) {
                // The part goes back out as it arrived: its members were read whole off the wire, so
                // the protocol spells them again untouched, where this part sits.
                entries.add(raw.members());
            } else if (part instanceof MediaPart mediaPart) {
                entries.add(mediaEntry(mediaPart));
            } else {
                throw unsupportedPart(part);
            }
        }
        return entries;
    }

    /**
     * Whether the message takes the array form of content: it does as soon as it is not text alone,
     * because an image cannot be spelled inside a string, and neither can a field this module does
     * not model.
     */
    private static boolean arrayContent(List<ContentPart> content) {
        return content.stream().anyMatch(part -> part instanceof MediaPart
                || part instanceof RawPart
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
        if (saysNothing(part.getText(), part.getExtras())) {
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
        putIfSet(entry, "call_id", part.getCallId());
        putIfSet(entry, "name", part.getName());
        putIfSet(entry, "arguments", part.getArgumentsJson());
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
        putIfSet(entry, "call_id", result.getCallId());
        Object output = output(result.getSubParts());
        // The output carries extras of its own when it takes the entry-array form, so the extras'
        // reach ends here: it goes in after the merges, unless an entry names it whole.
        if (messageExtras != null) {
            messageExtras.mergeInto(entry);
        }
        ProviderExtras resultExtras = result.getExtras();
        if (resultExtras != null) {
            resultExtras.mergeInto(entry);
        }
        if (!namesWhole(messageExtras, "output") && !namesWhole(resultExtras, "output")) {
            entry.put("output", output);
        }
        return entry;
    }

    /**
     * What a tool answered, as the protocol takes it: the text itself when the result is text alone,
     * and an array of content entries otherwise — a result that carried an image, or a field of its
     * own, has nowhere to go inside a string. A group among the result's parts goes out as the parts
     * it holds: this protocol has no layer to hold one part inside another, so the group is shape
     * rather than a node of its own.
     */
    private static Object output(List<ContentPart> parts) {
        List<ContentPart> flat = flatten(parts);
        if (flat.stream().allMatch(ResponsesWriter::plainText)) {
            return textOf(flat);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentPart part : flat) {
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

    /** Whether a part is text the string form can carry: a text part with no field of its own. */
    private static boolean plainText(ContentPart part) {
        return part instanceof TextPart text && (text.getExtras() == null || text.getExtras().isEmpty());
    }

    /**
     * The reasoning as the item it becomes, or {@code null} when it carries neither text nor a field
     * of its own and the message has nothing to put there either: this protocol keeps the summary
     * beside the answer rather than inside it, and carries the opaque companion that proves the
     * reasoning came from the model — both of which have to go back on the next turn for the model to
     * accept its own turn.
     */
    private static @Nullable Map<String, Object> reasoningItem(ReasoningPart part,
            @Nullable ProviderExtras messageExtras) {
        boolean hasText = part.getText() != null && !part.getText().isEmpty();
        if (saysNothing(part.getText(), part.getExtras()) && (messageExtras == null || messageExtras.isEmpty())) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "reasoning");
        if (hasText) {
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

    /**
     * The text the parts say, joined: what the string form of a tool's answer holds. The parts are
     * text alone, which the caller has established, and one that says nothing adds nothing.
     */
    private static String textOf(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart && textPart.getText() != null) {
                text.append(textPart.getText());
            }
        }
        return text.toString();
    }

    private Stream<Map<String, Object>> tools(List<Tool> requestTools) {
        return requestTools.stream().map(requestTool -> tool(requestTool.definition()));
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
        putIfSet(tool, "parameters", definition.getInputSchema());
        putIfSet(tool, "strict", definition.getStrict());
        definition.getExtras().mergeInto(tool);
        return tool;
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
                // A mode this protocol has no word for goes out as nothing, not as a refusal.
                return null;
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
            JsonSchema schema = format.getSchema();
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
     * Whether a message contributes anything at all: a part that becomes an item, or a field of its
     * own. One that does not is left out rather than sent as framing with nothing in it.
     */
    private static boolean saysSomething(ChatMessage message) {
        ProviderExtras extras = message.getExtras();
        return (extras != null && !extras.isEmpty())
                || flatten(message.getParts()).stream().anyMatch(ResponsesWriter::saysSomething);
    }

    /**
     * Whether a part becomes an item: a text or reasoning part carrying neither text nor a field of
     * its own says nothing, and every other part is an item of its own.
     */
    private static boolean saysSomething(ContentPart part) {
        if (part instanceof TextPart text) {
            return !saysNothing(text.getText(), text.getExtras());
        }
        if (part instanceof ReasoningPart reasoning) {
            return !saysNothing(reasoning.getText(), reasoning.getExtras());
        }
        return true;
    }

    /** Whether a text or reasoning part says nothing: no text and no field of its own. */
    private static boolean saysNothing(@Nullable String text, @Nullable ProviderExtras extras) {
        return (text == null || text.isEmpty()) && (extras == null || extras.isEmpty());
    }

    private static SynapseException unsupportedPart(ContentPart part) {
        return new SynapseException(
                "unsupported part type for OpenAI Responses: " + part.getClass().getSimpleName());
    }

}
