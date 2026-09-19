package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.exception.SynapseException;

/**
 * The front door for one conversation turn with a model provider: send the whole request, get the
 * whole answer.
 *
 * <p>
 * This is the seam between an application and a provider module. The library's own model
 * ({@link ChatRequest} / {@link ChatResponse}) is all a caller needs to know; which protocol speaks
 * underneath — OpenAI chat completions, OpenAI Responses, Anthropic messages, or anything later —
 * is decided by which implementation was instantiated, and never leaks through here.
 *
 * <p>
 * The call blocks until the response is complete and carries nothing between calls: every input
 * arrives on the request, and there is no conversation state the implementation is expected to
 * remember. Implementations must be stateless and safe to share across threads; failures are
 * thrown as {@code SynapseException} and its subclasses.
 *
 * <p>
 * Streaming is a first-class citizen by design and will live on this same interface as a sibling
 * method, sharing this request model with a separately modeled stream of events.
 */
public interface ChatClient {

    /**
     * Sends one chat request and blocks until the complete response arrives.
     *
     * @param request the whole call: messages so far, tools, the shape the answer should take, and
     *                    how to run it; never {@code null}
     * @return the provider's complete answer
     * @throws SynapseException the call failed — the request was
     *                              refused, or no answer could be obtained
     */
    ChatResponse chat(ChatRequest request);

}
