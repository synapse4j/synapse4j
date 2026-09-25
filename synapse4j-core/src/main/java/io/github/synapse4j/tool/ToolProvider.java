package io.github.synapse4j.tool;

import java.util.List;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.data.ChatRequest;

/**
 * The tools of one call, answered when the call is prepared — for a tool set that changes
 * behind the client, or is too expensive to build while the client is being assembled.
 *
 * <p>
 * This is the fetching counterpart to registration: {@link ChatClient#addDefaultTool(Tool)}
 * fixes a set at configuration time, while a provider is asked anew on every preparation,
 * with the request that is about to go out. What comes back joins the standing set under the
 * same merge rules a registered tool would meet — which calls see which tools is the
 * provider's to decide, and filtering an already-available set is a request customizer's job
 * rather than this one's.
 *
 * <p>
 * The client passed in is the one asking, so one instance registered on several clients can
 * tell them apart. It is there to be read: a provider is a source of tools, not a place to
 * drive the conversation.
 *
 * <p>
 * An implementation is asked on the calling thread, before the request is sent; a failure it
 * throws fails the call. An answer of {@code null} is a contract violation and fails loudly
 * where the answer lands, not somewhere downstream.
 */
@FunctionalInterface
public interface ToolProvider {

    /**
     * The tools to make available for one call.
     *
     * @param client  the client whose preparation is asking — the one this provider was
     *                    registered on
     * @param request the call about to go out; never {@code null}
     * @return the tools this call adds to the standing set; never {@code null}, and empty
     *         when there are none
     */
    List<Tool> tools(ChatClient client, ChatRequest request);

}
