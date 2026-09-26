package io.github.synapse4j.tool;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Ready-made error policies for {@link ToolExecutor} — each a named way of turning a failed
 * call into what the caller or the model sees.
 *
 * <p>
 * What earns a place here is a policy worth naming at the call site; the default failure text
 * is {@link #message(String)}, which an executor given no handler falls back on. Anything else
 * is a lambda at the point of wiring — the shape is one line, and an application that writes
 * its own keeps full control of the text, verbatim from handler to result.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorHandlers {

    /**
     * Answers with the exception's message, carrying the given prefix so the model reads a
     * failure as a failure — a bare message is all too easily taken at face value as tool
     * output. A message already starting with the prefix comes back verbatim; an empty one
     * falls back to the exception itself, prefix and all. An empty prefix answers the message
     * untouched.
     *
     * @param prefix the text every answer carries, for example {@code "Error: "}; never
     *                   {@code null}
     * @return the handler; never {@code null}
     */
    public static ToolExecutor.ErrorHandler message(@NonNull String prefix) {
        return (call, failure) -> {
            String message = failure.getMessage();
            if (message == null) {
                message = failure.toString();
            }
            return message.startsWith(prefix) ? message : prefix + message;
        };
    }

    /**
     * Always answers with the given text, whatever went wrong: the choice when a failure should
     * reach the model as an instruction — what happened, what to do next — rather than as
     * whatever detail the exception carried, and the way to keep internals off the wire
     * entirely.
     *
     * @param message the text handed back as this call's result, verbatim; never {@code null}
     * @return the handler; never {@code null}
     */
    public static ToolExecutor.ErrorHandler fixed(@NonNull String message) {
        return (call, failure) -> message;
    }

    /**
     * Lets the failure back out as it came: it propagates to whoever called
     * {@link ToolExecutor#execute}, ending the whole batch — the choice for an application
     * that takes failures into its own hands instead of handing them to the model.
     *
     * @return the handler; never {@code null}
     */
    public static ToolExecutor.ErrorHandler rethrow() {
        return (call, failure) -> {
            throw failure;
        };
    }

}
