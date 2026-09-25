package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.tool.ToolProvider;

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
 * validated and sent — where an application corrects a shared field whose mapping does not fit the
 * endpoint — {@link ChatResponseCustomizer}s, which adjust an answer on its way back,
 * {@link ChatStreamEventCustomizer}s, which adapt a stream's events before they are folded, and a
 * set of default tools merged into every request's own, and tool providers whose tools are
 * fetched on every call instead. {@link AbstractChatClient} implements these
 * parts for an implementation; a client that implements this interface directly carries the same
 * obligation.
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
     * Customizers run in the order they were added, on the calling thread — the client applies
     * its own defaults first, so every customizer sees them applied and has the last word on what
     * goes out. The same customizer may be added more than once, and then runs once per addition.
     * A client shared across threads hands each call a consistent list, so a customizer must
     * itself be safe to run concurrently.
     *
     * @param customizer the customizer to add; never {@code null}
     */
    void addChatRequestCustomizer(ChatRequestCustomizer customizer);

    /**
     * Removes every registration of the given customizer. Since a lambda equals only itself, the
     * caller has to keep the reference it added.
     *
     * @param customizer the customizer to remove; never {@code null}
     * @return whether any was removed
     */
    boolean removeChatRequestCustomizer(ChatRequestCustomizer customizer);

    /**
     * Adds a customizer that adjusts every answer after this client has finished with it.
     *
     * <p>
     * Customizers run in the order they were added, on the calling thread, after the client's own
     * steps, and the last one's answer is what the caller receives. For a streamed answer they run
     * once, when the stream runs to its end; an answer that failed runs none. The same customizer
     * may be added more than once, and then runs once per addition. A client shared across threads
     * hands each call a consistent list, so a customizer must itself be safe to run concurrently.
     *
     * @param customizer the customizer to add; never {@code null}
     */
    void addChatResponseCustomizer(ChatResponseCustomizer customizer);

    /**
     * Removes every registration of the given customizer. Since a lambda equals only itself, the
     * caller has to keep the reference it added.
     *
     * @param customizer the customizer to remove; never {@code null}
     * @return whether any was removed
     */
    boolean removeChatResponseCustomizer(ChatResponseCustomizer customizer);

    /**
     * Adds a customizer that adapts every event of every stream this client opens, between the
     * stream's source and its folding.
     *
     * <p>
     * Customizers run in the order they were added, on the thread pulling the events, and the
     * fold and the caller both see their answers: what is folded and what is handed out is the
     * same event. The chain is snapshotted when a stream opens; one registered mid-flight joins
     * neither that stream nor its fold. A blocking call has no events, so a registration here
     * runs only on streams. The same customizer may be added more than once, and then runs once
     * per addition. A client shared across threads hands each call a consistent list, so a
     * customizer must itself be safe to run concurrently.
     *
     * @param customizer the customizer to add; never {@code null}
     */
    void addChatStreamEventCustomizer(ChatStreamEventCustomizer customizer);

    /**
     * Removes every registration of the given customizer. Since a lambda equals only itself, the
     * caller has to keep the reference it added.
     *
     * @param customizer the customizer to remove; never {@code null}
     * @return whether any was removed
     */
    boolean removeChatStreamEventCustomizer(ChatStreamEventCustomizer customizer);

    /**
     * Registers a tool this client merges into every request it sends, beside the ones the request
     * itself carries, so a standing tool set need not be restated per call. Registration is
     * configuration, meant for the time before the client is shared; a call already in flight sees
     * either set, never a half-written one.
     *
     * <p>
     * A name is unique among the defaults: registering a name that is already registered replaces
     * that tool where it sits, so upgrading an implementation does not shuffle the rest.
     *
     * <p>
     * The defaults meet the request's own tools at the client's defaults step of every call, in
     * one fixed shape: the defaults in registration order, a request tool of the same name
     * standing in its slot for that call, then the request's remaining tools in the order the
     * request lists them. The same defaults and the same request therefore always go out in the
     * same order. Names duplicated within the request itself are the caller's to avoid.
     *
     * @param tool the tool to register; never {@code null}, and its definition's name must not be
     *                 {@code null}
     */
    void addDefaultTool(Tool tool);

    /**
     * Removes the default tool registered under the given name, so requests no longer carry it.
     *
     * @param name the name it was registered under; never {@code null}
     * @return whether a default tool of that name was registered
     */
    boolean removeDefaultTool(String name);

    /**
     * Adds a source whose tools are fetched on every call rather than registered up front —
     * for a tool set that changes behind the client, or one too expensive to build while the
     * client is being assembled.
     *
     * <p>
     * The provider is asked on the calling thread while the request is being prepared, and
     * given this client and the request about to go out. Registration records presence, not
     * multiplicity: the same source registered twice is still one source and is asked once.
     * Its answer joins the standing set under the merge the defaults step already runs: the
     * defaults fill the slots first, each provider then fills them in registration order, and
     * the request's own tools go last — a later source wins by name at the slot the name first
     * took, a new name appends, so the caller always has the last word. A failure the
     * provider throws fails the call, and an answer of {@code null} is refused where it
     * lands.
     *
     * @param provider the source to ask; never {@code null}
     */
    void addToolProvider(ToolProvider provider);

    /**
     * Removes the given provider, so it is no longer asked. Since a lambda equals only
     * itself, the caller has to keep the reference it added.
     *
     * @param provider the provider to remove; never {@code null}
     * @return whether it was registered
     */
    boolean removeToolProvider(ToolProvider provider);

}
