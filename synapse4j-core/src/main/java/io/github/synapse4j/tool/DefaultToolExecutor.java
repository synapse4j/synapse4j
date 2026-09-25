package io.github.synapse4j.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.ToolNotFoundException;

/**
 * The default {@link ToolExecutor}: a name resolves against the tools at hand, a failure goes
 * through one {@link ToolExecutor.ErrorHandler}, and the answers come back paired with their
 * calls. A batch is answered or aborts — unless a turn cap is configured and the round has
 * reached it, in which case it is declined and the round ends where it stands.
 *
 * <p>
 * How the batch runs depends on what it was built with. With no workers, each call runs inline
 * on the calling thread, in order — an abort keeps the later calls from ever starting. With an
 * {@link ExecutorService}, the whole batch runs concurrently: results are still assembled in
 * call order, an abort waits for every call to finish and then rethrows the first failure in
 * that order, and nothing already running is cancelled — abandoning a call cannot undo what it
 * has already done. A submission the service refuses leaves straight away too; what was already
 * submitted keeps running, unwaited — the service's policy meeting the batch, not this executor's
 * to override. The handler runs on whichever thread ran the call, so one shared across
 * runs must be safe to run concurrently. The service belongs to whoever passed it: this
 * executor never shuts it down.
 *
 * <p>
 * With no handler configured, a failure is answered with its message under the prefix
 * {@code "Error: "} — {@link ErrorHandlers#message(String)}. A name that resolves against
 * nothing fails with {@link ToolNotFoundException}, an answer of {@code null} counts as a
 * failure, and both travel the same path. Only an interruption comes straight out: the
 * interrupt status is restored and no handler sees it.
 */
public class DefaultToolExecutor implements ToolExecutor {

    /** The marking a failure carries when the caller configured no handler of their own. */
    private static final String DEFAULT_PREFIX = "Error: ";

    private final ExecutorService workers;

    private final ToolExecutor.ErrorHandler failures;

    private final int maxTurns;

    /** Runs the batch inline, in order, with no cap on the round, answering failures with the prefixed message. */
    public DefaultToolExecutor() {
        this(null, null, 0);
    }

    /**
     * @param workers  how the batch runs: {@code null} for inline on the calling thread, in
     *                     order; a service for the whole batch concurrently — never shut down here
     * @param failures where a failed call goes; {@code null} for the failure's message under
     *                     {@code "Error: "}
     */
    public DefaultToolExecutor(ExecutorService workers, ToolExecutor.ErrorHandler failures) {
        this(workers, failures, 0);
    }

    /**
     * @param workers  how the batch runs: {@code null} for inline on the calling thread, in
     *                     order; a service for the whole batch concurrently — never shut down here
     * @param failures where a failed call goes; {@code null} for the failure's message under
     *                     {@code "Error: "}
     * @param maxTurns the most turns a round may have spent by the time a batch arrives: at or
     *                     past that count the batch is declined; {@code 0} for no cap. Consulted
     *                     against the context's turn, which only a loop maintains — a bare call
     *                     still reads {@code 0} and runs
     */
    public DefaultToolExecutor(ExecutorService workers, ToolExecutor.ErrorHandler failures, int maxTurns) {
        this.workers = workers;
        this.failures = failures != null ? failures : ErrorHandlers.message(DEFAULT_PREFIX);
        this.maxTurns = maxTurns;
    }

    @Override
    public List<ToolResultPart> execute(List<ToolCallPart> calls, List<Tool> available, ChatContext context)
            throws Exception {
        Objects.requireNonNull(calls, "calls must not be null");
        Objects.requireNonNull(available, "available must not be null");
        if (calls.isEmpty()) {
            return new ArrayList<>();
        }
        int turn = context != null ? context.getTurn() : 0;
        if (maxTurns > 0 && turn >= maxTurns) {
            return null;
        }
        if (workers == null) {
            List<ToolResultPart> results = new ArrayList<>(calls.size());
            for (ToolCallPart call : calls) {
                results.add(run(call, available, context));
            }
            return results;
        }
        List<Future<ToolResultPart>> futures = new ArrayList<>(calls.size());
        for (ToolCallPart call : calls) {
            futures.add(workers.submit(() -> run(call, available, context)));
        }
        // Collect every future before deciding: an abort waits for the whole batch, and the
        // failure that wins is the first by call order, not whoever finished first.
        List<ToolResultPart> results = new ArrayList<>(calls.size());
        Throwable abort = null;
        for (Future<ToolResultPart> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                if (abort == null) {
                    abort = e.getCause();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        if (abort != null) {
            if (abort instanceof Exception exception) {
                throw exception;
            }
            if (abort instanceof Error error) {
                throw error;
            }
            throw new SynapseException("tool call failed", abort);
        }
        return results;
    }

    /**
     * One call, answered: through the tool on the way out, through the handler when anything
     * goes wrong — the handler's text coming back as a failed result, the handler itself
     * throwing to let the failure out of here.
     */
    private ToolResultPart run(ToolCallPart call, List<Tool> available, ChatContext context) throws Exception {
        ToolResultPart result = new ToolResultPart();
        result.setCallId(call.getCallId());
        result.setName(call.getName());
        try {
            Tool tool = resolve(available, call.getName());
            if (tool == null) {
                throw new ToolNotFoundException(call.getName());
            }
            String answer = Objects.requireNonNull(
                    tool.execute(call.getArgumentsJson(), context),
                    "tool answered null");
            result.getParts().add(new TextPart(answer));
        } catch (InterruptedException interruption) {
            // The caller's thread being told to stop is not this call's failure to report: the
            // status comes back and the interruption leaves, no handler seeing it — the way the
            // class javadoc has always promised.
            Thread.currentThread().interrupt();
            throw interruption;
        } catch (Exception failure) {
            String text = Objects.requireNonNull(
                    failures.handle(call, failure),
                    "error handler answered null");
            result.getParts().add(new TextPart(text));
            result.setError(true);
        }
        return result;
    }

    /** The first tool carrying that name, or {@code null} when none does. */
    private static Tool resolve(List<Tool> available, String name) {
        for (Tool tool : available) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

}
