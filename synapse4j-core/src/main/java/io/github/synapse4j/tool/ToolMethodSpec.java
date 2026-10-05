package io.github.synapse4j.tool;

import java.lang.reflect.Method;
import java.util.List;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.jspecify.annotations.Nullable;

/**
 * One method on its way to becoming a tool: the Java side it was read from, and the values its
 * annotations wrote.
 *
 * <p>
 * This is a container and nothing more — it reads no signature, fills no default, and refuses
 * nothing. {@link MethodTools} builds one and decides what goes into it, a
 * {@link ToolMethodSpecCustomizer} rewrites the strings, and {@link MethodTool} is what refuses a
 * resolution it could not be built from. Nothing is filled in along the way: a blank string is one
 * nobody supplied, which is what a customizer needs to tell from a value somebody wrote.
 *
 * <p>
 * The Java side is what the reader settled and does not change afterwards: the method, the instance
 * it runs on, and one entry per declared parameter, in signature order. The strings are written and
 * rewritten — the annotations write them, the customizers rewrite them, and the reader applies
 * whatever defaults are left over.
 */
@Getter
@Setter
@ToString(exclude = "target")
@RequiredArgsConstructor
public class ToolMethodSpec {

    /** The method the values were read from; the signature the declaration will be built from. */
    @NonNull
    private final Method method;

    /** The instance an instance method runs on; {@code null} for a static one. */
    private final @Nullable Object target;

    /** The name the tool is known by; blank when nothing supplied one. */
    @NonNull
    private String name = "";

    /** What the tool does; blank when nothing supplied it. */
    @NonNull
    private String description = "";

    /** Which implementation builds this tool; blank when nothing supplied one. */
    @NonNull
    private String type = "";

    /** One entry per declared parameter, in signature order; whatever the reader found. */
    @NonNull
    private final List<ToolParameterSpec> parameters;
}