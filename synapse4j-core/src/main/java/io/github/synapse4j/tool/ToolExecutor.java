package io.github.synapse4j.tool;

import java.util.List;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;

/**
 * Runs one round's worth of tool calls: each call the model made, resolved by name against the
 * tools available, answered with results the next request can carry back.
 *
 * <p>
 * This is the layer between whoever drives the round trips and {@link Tool#execute} — the home
 * for the policy that belongs to neither: the order or concurrency a batch runs under, where a
 * failure goes, and what a call naming no available tool receives. A single tool stays a plain
 * execution that throws and does not dispose; the client stays out of execution altogether.
 *
 * <p>
 * An implementation answers with one result per call, in the order the calls were given, each
 * carrying its call's id — a protocol requires every call to be answered, so a failed call
 * answers as a result too, unless the whole batch aborts by throwing. It may also decline the
 * batch outright: then no call runs at all and the round ends where it stands.
 */
public interface ToolExecutor {

    /**
     * Runs the given calls against the available tools.
     *
     * @param calls     the calls the model made, in the order it made them; never {@code null}
     * @param available the tools these calls resolve against — the set the request went out
     *                      with; never {@code null}
     * @param context   the conversation this round belongs to; {@code null} when none was
     *                      attached
     * @return one result per call, in the same order, each carrying its call's id, never
     *         shorter than {@code calls} — or {@code null} to decline the batch: not one call
     *         runs, the round ends where it stands, and the response that carried the calls
     *         is the round's output with them unanswered
     * @throws Exception if the batch aborts — the error policy rethrew, or resolution itself
     *                       failed; carried openly, decided by the caller
     */
    List<ToolResultPart> execute(List<ToolCallPart> calls,
            List<Tool> available,
            ChatContext context) throws Exception;

    /**
     * Where a failed call goes: back to the model as text it can read and retry against, or out
     * to the caller. A default executor is given one of these; an implementation of
     * {@link ToolExecutor} may follow its own policy and ignore this type entirely.
     *
     * <p>
     * The executor hands this text back untouched — whatever the handler answers is exactly
     * what the model reads, any marking included. With none supplied, a failure is answered
     * with its message marked as an error (an empty one falling back to the exception itself),
     * so a failure reads as a failure and not as tool output — the shape
     * {@link ErrorHandlers#message(String)} spells out.
     */
    @FunctionalInterface
    interface ErrorHandler {

        /**
         * Decides what a failed call becomes.
         *
         * @param call    the call that failed
         * @param failure what went wrong
         * @return the error text to hand back to the model as this call's result
         * @throws Exception to abort the batch — it propagates to whoever called
         *                       {@link ToolExecutor#execute}
         */
        String handle(ToolCallPart call, Exception failure) throws Exception;
    }

}
