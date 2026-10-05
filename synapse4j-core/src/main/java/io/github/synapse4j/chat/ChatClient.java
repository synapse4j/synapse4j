package io.github.synapse4j.chat;

import java.util.List;

import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.tool.ToolProvider;
import org.jspecify.annotations.Nullable;

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
 * A client may also carry {@link ChatCustomizer}s: hooks that prepare every request before it is
 * validated and sent — where an application corrects a shared field whose mapping does not fit
 * the endpoint — adjust an answer on its way back, adapt a stream's events before they are
 * folded; a set of default tools merged into every request's own, and tool providers whose
 * tools are fetched on every call instead; and default {@link ChatOptions} that fill in what a
 * request leaves unstated. {@link AbstractChatClient} implements these parts for an
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
     * @param request the whole call: the conversation this call carries, tools, the shape the
     *                    answer should take, and how to run it; never {@code null}
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
     * Folds one answer into the request, called exactly once per answer received: the caller owes
     * one call per answer — the tool-calling loop calls it for the answers it consumes, the caller
     * for the last one.
     *
     * <p>
     * This is also where the conversation is recorded: what the call sent moves from the pending
     * messages into the history before the answer joins it, so one call per answer archives the
     * sent round and folds the new turn in together. Skipping a call leaves its input pending, and
     * the next call sends it again.
     *
     * <p>
     * The default folds the answer's turn into the request's history and adopts the answer's
     * context onto a request that carries none of its own, so a request rebuilt from storage —
     * with a conversation but no context — picks up the session id the answer rode back on. A
     * protocol client overrides this to also record what its own continuation needs — and to
     * decide, as this default does not, which of the two lists the answer belongs in.
     *
     * @param request the request the conversation goes on with; never {@code null}
     * @param answer  the answer whose turn joins the conversation; never {@code null}
     */
    default void continueWith(ChatRequest request, ChatResponse answer) {
        if (request.getContext() == null && answer.getContext() != null) {
            request.setContext(answer.getContext());
        }
        request.getHistoryMessages().addAll(request.getPendingMessages());
        request.getPendingMessages().clear();
        request.addHistoryMessage(answer.getMessage());
    }

    /**
     * Adds a customizer: its request hook runs before each send this client makes, its response
     * hook on each answer, its stream hook on each event of each stream — whichever it
     * overrides, the rest stand idle. Customizers run in the order they were added; a client
     * shared across threads hands each call a consistent list, so a customizer must itself be
     * safe to run concurrently.
     *
     * @param customizer the customizer to add; never {@code null}
     */
    void addChatCustomizer(ChatCustomizer customizer);

    /**
     * Removes every registration of the given customizer. An instance removes only itself, so
     * the caller has to keep the reference it added.
     *
     * @param customizer the customizer to remove; never {@code null}
     * @return whether any was removed
     */
    boolean removeChatCustomizer(ChatCustomizer customizer);

    /**
     * Answers the customizers this client runs, in the order they were added. The list is a
     * snapshot: a customizer added or removed after it is taken joins or leaves no call already
     * under way.
     *
     * @return the customizers, in execution order; never {@code null}
     */
    List<ChatCustomizer> chatCustomizers();

    /**
     * Sets the options every call this client sends inherits from, so a standing model, temperature
     * or response format need not be repeated on each request. The defaults fill in what the call
     * leaves unstated: a field the call leaves {@code null} takes the default's value, and the two
     * bags merge with the call's entries winning by key. They are applied before any customizer
     * runs, so a customizer sees the merged options and still has the last word on what goes out.
     *
     * <p>
     * Setting is configuration, meant for the time before the client is shared: to change it while
     * shared, hand in a new instance rather than mutating the one already set, which a call in
     * flight may be reading.
     *
     * @param options the options to inherit from; never {@code null}
     */
    void setDefaultOptions(ChatOptions options);

    /**
     * Answers the options every call this client sends inherits from.
     *
     * @return the default options, or {@code null} when none were set
     */
    @Nullable
    ChatOptions defaultOptions();

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
     * Answers the tools this client merges into every request, in registration order. The list is
     * a snapshot, so it never changes under a caller already walking it.
     *
     * @return the default tools, in registration order; never {@code null}
     */
    List<Tool> defaultTools();

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

    /**
     * Answers the tool sources this client asks on every call, in registration order, without
     * duplicates. The list is a snapshot, so it never changes under a caller already walking it.
     *
     * @return the tool providers, in registration order; never {@code null}
     */
    List<ToolProvider> toolProviders();

}
