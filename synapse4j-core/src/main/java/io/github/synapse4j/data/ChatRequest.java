package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import io.github.synapse4j.tool.Tool;
import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

/**
 * One call: the conversation so far, what the model may call, the shape the answer should take, and
 * how to run it.
 *
 * <p>
 * The collections start empty, the configured members are never null, and the context is absent
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
public class ChatRequest {

    /** The conversation so far, oldest first. Never {@code null}; empty means no messages yet. */
    private final List<ChatMessage> messages = new ArrayList<>();

    /**
     * Tools the model may call. Never {@code null}; empty means none. Carries the whole
     * {@link Tool}, not just its declaration, so what is sent and what can run are the same set.
     */
    private final List<Tool> tools = new ArrayList<>();

    /** The shape the answer should take. Never {@code null}; with nothing set, nothing is asked. */
    @NonNull
    private ChatResponseFormat responseFormat = new ChatResponseFormat();

    /** How to run this call. Never {@code null}; with nothing set, the defaults stand. */
    @NonNull
    private ChatOptions options = new ChatOptions();

    /**
     * The context tying this call to a conversation; {@code null} until one is attached. It travels
     * inside the library only and is never serialized.
     */
    private @Nullable ChatContext context;

    /**
     * Adds a message to the conversation.
     *
     * @param message the message to add, oldest first
     * @return this call
     */
    public ChatRequest addMessage(@NonNull ChatMessage message) {
        messages.add(message);
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
     * Continues this call from an answer: the answer's turn joins the conversation, and the context
     * the answer rode back on becomes this call's.
     *
     * <p>
     * The context is the one the exchange ran on — it holds the request as it went out, the answer,
     * the turn, and whatever session id was adopted — so taking it is what keeps the next call part
     * of the same conversation when the caller builds a fresh request rather than growing this one.
     * An answer carrying no context leaves whatever this call already has; in the ordinary case the
     * two are the same instance and there is nothing to take.
     *
     * @param answer the answer whose turn continues the conversation
     * @return this call
     */
    public ChatRequest continueWith(@NonNull ChatResponse answer) {
        messages.add(answer.getMessage());
        if (answer.getContext() != null) {
            context = answer.getContext();
        }
        return this;
    }

    /**
     * The messages and tools are counted rather than printed: they grow with the conversation, and a
     * printout that carried them would grow just as long.
     *
     * @return this call, in brief
     */
    @Override
    public String toString() {
        return "ChatRequest(messages=" + messages.size() + ", tools=" + tools.size() + ", responseFormat="
                + responseFormat + ", options=" + options + ')';
    }

}
