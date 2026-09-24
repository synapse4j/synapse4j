package io.github.synapse4j.data;

/**
 * A tool the model may call, in both of its halves: the declaration that is sent, and the execution
 * that runs when the model calls it.
 *
 * <p>
 * {@link #definition()} is what reaches the wire — the adapters write it into the request and know
 * nothing about the rest of this interface. {@link #execute(String, ChatContext)} never leaves the
 * process: it is invoked by whatever drives the round trips, with the arguments the model produced,
 * and its answer becomes the result handed back to the model.
 *
 * <p>
 * A declaration with no executor behind it is the other half of the story: a wrapper among the
 * implementations carries one. An implementation is registered once and may be shared across
 * concurrent requests, so it holds no per-call state; everything a particular call needs arrives
 * as arguments.
 *
 * <p>
 * Executing a tool does I/O against the world, so a failure is whatever the tool itself throws,
 * declared openly rather than wrapped here. What becomes of that failure when one is thrown is the
 * caller's policy, not this contract's.
 */
public interface Tool {

    /**
     * The declaration of this tool, for the request.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    ToolDefinition definition();

    /**
     * The name the model calls this tool by — its identity on the wire, used wherever a tool is
     * keyed by name. The default reads it off the declaration; an implementation that already
     * holds it may return it directly.
     *
     * @return this tool's name
     */
    default String name() {
        return definition().getName();
    }

    /**
     * Runs this tool against the given arguments.
     *
     * @param arguments the arguments the model produced, as JSON text
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the result to hand back to the model; never {@code null}
     * @throws Exception if execution fails — carried openly, decided by the caller
     */
    String execute(String arguments, ChatContext context) throws Exception;

}
