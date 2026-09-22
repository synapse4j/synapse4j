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
 * A client may also carry {@link ChatRequestCustomizer}s, which prepare every request before it is
 * validated and sent. That is where an application corrects a shared field whose mapping does not
 * fit the endpoint it is talking to. {@link AbstractChatClient} implements this part for an
 * implementation; a client that implements this interface directly carries the same obligation.
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

    /**
     * Adds a customizer that prepares every request before this client validates and sends it.
     *
     * <p>
     * Customizers run in the order they were added, on the calling thread. The same customizer may
     * be added more than once, and then runs once per addition. A client shared across threads
     * hands each call a consistent list, so a customizer must itself be safe to run concurrently.
     *
     * @param customizer the customizer to add; never {@code null}
     */
    void addChatRequestCustomizer(ChatRequestCustomizer customizer);

    /**
     * Removes the first customizer equal to the given one. Since a lambda equals only itself, the
     * caller has to keep the reference it added.
     *
     * @param customizer the customizer to remove; never {@code null}
     * @return whether one was removed
     */
    boolean removeChatRequestCustomizer(ChatRequestCustomizer customizer);

}
