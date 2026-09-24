package io.github.synapse4j.tool;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.Tool;
import io.github.synapse4j.data.ToolDefinition;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

/**
 * A {@link Tool} built from a declaration and the code behind it.
 *
 * <p>
 * The declaration is everything the model sees; the {@link Executor} is everything the library
 * sees when the model calls. Both arrive through {@link #of(ToolDefinition, Executor)} — a lambda
 * is the expected shape, and parsing the arguments or shaping the result is that lambda's own
 * business, done with whichever JSON library the application already has.
 *
 * <p>
 * {@link #of(ToolDefinition)} builds the other half of the story: a declaration with nothing
 * behind it. {@link #execute(String, ChatContext)} on such a tool fails with {@link
 * UnsupportedOperationException} — a caller that wanted to run it has the wrong tool, and the
 * message says which.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FunctionTool implements Tool {

    /**
     * The code behind a tool: the model's arguments in, the model's answer out. Named and shaped
     * like {@link Tool#execute(String, ChatContext)} because that is what it is — the body of it,
     * for tools assembled from a declaration plus a lambda rather than written as a class.
     */
    @FunctionalInterface
    public interface Executor {

        /**
         * Runs the tool against the given arguments.
         *
         * @param arguments the arguments the model produced, as JSON text
         * @param context   the conversation this call belongs to; {@code null} when none was
         *                      attached
         * @return the result to hand back to the model; never {@code null}
         * @throws Exception if execution fails — carried openly, decided by the caller
         */
        String execute(String arguments, ChatContext context) throws Exception;
    }

    /** The declaration this tool carries. */
    private final ToolDefinition definition;

    /** The code behind this tool; {@code null} when the tool is a declaration only. */
    private final Executor executor;

    /**
     * A tool the model may see and the library may not run: the declaration alone, for callers
     * that execute tools themselves or hand them elsewhere.
     *
     * @param definition the declaration to carry; never {@code null}
     * @return the declaration-backed tool
     */
    public static FunctionTool of(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        return new FunctionTool(definition, null);
    }

    /**
     * A tool with code behind it.
     *
     * @param definition the declaration to carry; never {@code null}
     * @param executor   the code to run on a call; never {@code null}
     * @return the assembled tool
     */
    public static FunctionTool of(ToolDefinition definition, Executor executor) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        return new FunctionTool(definition, executor);
    }

    /**
     * The declaration of this tool, for the request.
     *
     * @return this tool as the model sees it; never {@code null}
     */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * Runs this tool against the given arguments.
     *
     * @param arguments the arguments the model produced, as JSON text
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the result to hand back to the model; never {@code null}
     * @throws UnsupportedOperationException if this tool is a declaration with no executor
     * @throws Exception                     if execution fails — carried openly, decided by the
     *                                           caller
     */
    @Override
    public String execute(String arguments, ChatContext context) throws Exception {
        if (executor == null) {
            throw new UnsupportedOperationException(
                    "tool '" + definition.getName() + "' was declared without an executor");
        }
        return executor.execute(arguments, context);
    }

    /**
     * The code behind this tool.
     *
     * @return the executor; {@code null} when this tool is a declaration only
     */
    public Executor executor() {
        return executor;
    }

}
