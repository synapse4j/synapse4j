package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.tool.Tool;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

/**
 * One call: the conversation this call carries, what the model may call, and how to run it. The
 * shape the answer should take sits in the options.
 *
 * <p>
 * The conversation is carried in two lists plus one slot. {@code historyMessages} is the
 * conversation as it stands — everything an exchange has already covered; {@code pendingMessages}
 * is what this call sends, every message in it going out on every call. Together they are the
 * whole conversation:
 * neither list is ever trimmed here, so trimming is the application's deliberate act. The system
 * message sits outside the turn sequence as the framing the model answers under — one slot that
 * replaces rather than accumulates, and may hold none.
 *
 * <p>
 * The lists start empty, the configured members are never null, and the context is absent
 * until one is attached, so a caller fills in what it needs. An empty list means "none of these"; a
 * {@link ChatOptions} with nothing set means "no opinion", and the defaults the client was built
 * with stand.
 *
 * <p>
 * There is deliberately no bag of provider-specific fields here. The call-level escape hatches live
 * in {@link ChatOptions}, next to the configured defaults they override; the node-level ones live on
 * the node they belong to — a message, a part, a tool.
 */
@Getter
@Setter
@NoArgsConstructor
public class ChatRequest {

    /**
     * The instructions the model answers under, or {@code null} when this call carries none. Set, never accumulated:
     * the same slot is overwritten, so changing it changes the next call.
     */
    private @Nullable ChatMessage systemMessage;

    /** The conversation as it stands — everything an exchange has already covered, oldest first. Never {@code null}. */
    private final List<ChatMessage> historyMessages = new ArrayList<>();

    /** Everything this call sends, oldest first — every message here goes out on every call. Never {@code null}. */
    private final List<ChatMessage> pendingMessages = new ArrayList<>();

    /**
     * Tools the model may call. Never {@code null}; empty means none. Carries the whole
     * {@link Tool}, not just its declaration, so what is sent and what can run are the same set.
     */
    private final List<Tool> tools = new ArrayList<>();

    /** How to run this call. Never {@code null}; with nothing set, the defaults stand. */
    @NonNull
    private ChatOptions options = new ChatOptions();

    /**
     * The context tying this call to a conversation; {@code null} until one is attached. It travels
     * inside the library only and is never serialized.
     */
    private @Nullable ChatContext context;

    /**
     * A copy of another call: the same messages in this copy's own lists, the same context, and its
     * own tools and options. Nothing the copy changes reaches back into the one it was copied from.
     */
    public ChatRequest(ChatRequest other) {
        this.systemMessage = other.systemMessage;
        this.historyMessages.addAll(other.historyMessages);
        this.pendingMessages.addAll(other.pendingMessages);
        this.tools.addAll(other.tools);
        this.options = other.options.copy();
        this.context = other.context;
    }

    /**
     * Adds a message to what this call will send.
     *
     * @param message the message to add, oldest first
     * @return this call
     */
    public ChatRequest addPendingMessage(@NonNull ChatMessage message) {
        pendingMessages.add(message);
        return this;
    }

    /**
     * Adds a message to the conversation as it stands — something an earlier exchange already
     * covered.
     *
     * @param message the message to add, oldest first
     * @return this call
     */
    public ChatRequest addHistoryMessage(@NonNull ChatMessage message) {
        historyMessages.add(message);
        return this;
    }

    /**
     * Adds a tool the model may call.
     *
     * @param tool the tool to add
     * @return this call
     */
    public ChatRequest addTool(@NonNull Tool tool) {
        tools.add(tool);
        return this;
    }

    /**
     * Sets the instructions the model answers under, as a system message saying the given text —
     * the framing a conversation usually opens with. The same slot is overwritten: a second call
     * replaces the framing rather than adding to it.
     *
     * @param text what the message says
     * @return this call
     */
    public ChatRequest systemMessage(@NonNull String text) {
        this.systemMessage = ChatMessage.system(text);
        return this;
    }

    /**
     * Adds a message from the user saying the given text to what this call will send — the shape
     * most of a conversation is built from.
     *
     * @param text what the message says
     * @return this call
     */
    public ChatRequest addUserMessage(@NonNull String text) {
        return addPendingMessage(ChatMessage.user(text));
    }

    /**
     * The messages are counted rather than printed: they grow with the conversation, and a
     * printout that carried them would grow just as long. The system message is reported by
     * presence alone, for the same reason.
     *
     * @return this call, in brief
     */
    @Override
    public String toString() {
        return "ChatRequest(systemMessage=" + (systemMessage != null) + ", historyMessages="
                + historyMessages.size() + ", pendingMessages=" + pendingMessages.size() + ", tools="
                + tools.size() + ", options=" + options + ')';
    }

}
