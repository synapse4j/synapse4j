package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * One call: the conversation so far, what the model may call, the shape the answer should take, and
 * how to run it.
 *
 * <p>
 * Nothing here is nullable and the collections start empty, so a caller fills in what it needs and
 * nobody walking the request has to check for null. An empty list means "none of these"; a
 * {@link ChatOptions} with nothing set means "no opinion", and the defaults the client was built
 * with stand.
 *
 * <p>
 * There is deliberately no bag of provider-specific fields here. The call-level escape hatches live
 * in {@link ChatOptions}, next to the configured defaults they override; the node-level ones live on
 * the node they belong to — a message, a part, a tool.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** The conversation so far, oldest first. Never {@code null}; empty means no messages yet. */
    @NonNull
    private List<ChatMessage> messages = new ArrayList<>();

    /** Tools the model may call. Never {@code null}; empty means none. */
    @NonNull
    private List<ToolDefinition> tools = new ArrayList<>();

    /** The shape the answer should take. Never {@code null}; with nothing set, nothing is asked. */
    @NonNull
    private ChatResponseFormat responseFormat = new ChatResponseFormat();

    /** How to run this call. Never {@code null}; with nothing set, the defaults stand. */
    @NonNull
    private ChatOptions options = new ChatOptions();

}
