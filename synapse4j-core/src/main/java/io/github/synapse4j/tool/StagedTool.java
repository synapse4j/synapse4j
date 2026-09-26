package io.github.synapse4j.tool;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A Tool whose execution falls into fixed stages: resolve the arguments, make the call, render
 * the result. {@link #execute} composes the three in that order, so an implementation provides
 * the stages and inherits the whole run.
 *
 * <p>
 * Where the information behind a stage comes from is the implementation's own affair — a
 * method's signature, type tokens handed in at construction, anything else. What this interface
 * fixes is the shape of the run: the order of the stages, and the fact that each one sees what
 * the previous one produced. Overriding {@link #execute} takes the whole run over; the stages
 * are then whatever the override makes of them, or unused.
 */
public interface StagedTool extends Tool {

    /**
     * Stage one: what the model produced, as the values the call will take.
     *
     * @param arguments the arguments the model produced, as JSON text; {@code null} or blank
     *                      means the model produced none
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the values for {@link #call}, in its order; the array itself is never {@code null}
     *         — a call taking nothing takes an empty array — while an element is {@code null}
     *         when its parameter has no value
     * @throws Exception if the text cannot be read — carried openly, decided by the caller
     */
    @Nullable
    Object[] resolveArguments(@Nullable String arguments, @Nullable ChatContext context) throws Exception;

    /**
     * Stage two, the one that acts: what stage one prepared, answered with what stage three will
     * render.
     *
     * @param values  the values resolved for this call; the array itself is never {@code null},
     *                    while an element is {@code null} when its parameter has no value
     * @param context the conversation this call belongs to; {@code null} when none was attached
     * @return what the call produced; may be {@code null}
     * @throws Exception if the call fails, as itself — no wrapping added on the way out;
     *                       {@link #execute} passes it through untouched
     */
    @Nullable
    Object call(@Nullable Object[] values, @Nullable ChatContext context) throws Exception;

    /**
     * Stage three: what the call produced, as the parts the model will read.
     *
     * @param returnValue what {@link #call} returned; may be {@code null}
     * @param context     the conversation this call belongs to; {@code null} when none was
     *                        attached
     * @return the result as the model sees it, in order; never {@code null}, possibly empty
     * @throws Exception if the value cannot be rendered — carried openly, decided by the caller
     */
    List<ContentPart> resolveResult(@Nullable Object returnValue, @Nullable ChatContext context) throws Exception;

    /**
     * Runs the three stages in order: resolve, call, render. A failure from any stage passes
     * through as itself — what becomes of it is the caller's policy, as it is for any {@link
     * Tool}.
     *
     * @param arguments the arguments the model produced, as JSON text
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the parts to hand back to the model; never {@code null}
     * @throws Exception if any stage fails — carried openly, decided by the caller
     */
    @Override
    default List<ContentPart> execute(@Nullable String arguments, @Nullable ChatContext context) throws Exception {
        Object[] values = resolveArguments(arguments, context);
        Object result = call(values, context);
        return resolveResult(result, context);
    }

}
