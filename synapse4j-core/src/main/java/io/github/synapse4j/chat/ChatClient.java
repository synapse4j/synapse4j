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
 * Both ways of answering are on this same interface and share the same request model: {@link #chat}
 * blocks until the whole answer arrives, {@link #stream} pulls it event by event and assembles the
 * same {@link ChatResponse} as it goes. Implementations must provide both.
 *
 * <p>
 * The calls carry nothing between calls: every input arrives on the request, and there is no
 * conversation state the implementation is expected to remember. Implementations must be stateless
 * and safe to share across threads; failures are thrown as {@code SynapseException} and its
 * subclasses.
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

    /**
     * Sends one chat request and answers it as a stream of events, one per protocol event, in
     * arrival order.
     *
     * <p>
     * Iterating the returned stream pulls events and is where failures surface: a request the
     * provider refuses fails here, before anything is returned; a connection that drops mid-answer
     * fails while iterating. The caller closes the stream to cancel an answer still in flight.
     *
     * @param request the whole call, exactly what {@link #chat} takes; never {@code null}
     * @return the streaming answer; never {@code null}
     * @throws SynapseException the request was refused before the stream could open
     */
    ChatStream stream(ChatRequest request);

}
