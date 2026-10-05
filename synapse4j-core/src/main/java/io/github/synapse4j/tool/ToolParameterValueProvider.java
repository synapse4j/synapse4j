package io.github.synapse4j.tool;

import io.github.synapse4j.data.ChatContext;

import org.jspecify.annotations.Nullable;

/**
 * Where an argument the model does not produce gets its value.
 *
 * <p>
 * A tool method parameter is declared to the model or it is not, and this is the answer for the ones
 * that are not: a {@link ChatContext}-typed parameter takes the conversation itself, and anything
 * else — a security principal, a locale, a Spring bean — has to come from somewhere the library does
 * not know about. That somewhere is one of these, written onto a parameter's own entry by a
 * {@link ToolMethodSpecCustomizer}, which is how an application fills an argument without subclassing
 * {@link MethodTool}.
 *
 * <p>
 * A customizer usually registers one provider for a whole type, so the same instance answers for
 * several parameters: {@code parameter} says which one is being asked about now, and {@code tool} says
 * which method it belongs to. A provider that has nothing to tell them apart can ignore both.
 */
@FunctionalInterface
public interface ToolParameterValueProvider {

    /**
     * The value to pass for this call's argument.
     *
     * @param tool      the resolution of the method the call belongs to; never {@code null}
     * @param parameter this argument's own entry, as it was resolved; never {@code null}
     * @param context   the conversation this call belongs to; {@code null} when none was attached
     * @return the value to pass; {@code null} when there is none
     */
    @Nullable
    Object get(ToolMethodSpec tool, ToolParameterSpec parameter, @Nullable ChatContext context);

}