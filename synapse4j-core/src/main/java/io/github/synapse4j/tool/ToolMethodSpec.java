package io.github.synapse4j.tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One annotated method, read: the Java side it was read from, and the values its annotations wrote.
 *
 * <p>
 * This is what a customizer sees and changes — a step of resolution, not a declaration: no schema
 * exists at this point, and neither does a tool. The Java side is fixed and read only; the strings are
 * the application's to rewrite, which is how a value from configuration reaches a tool.
 */
@Getter
@Setter
@ToString
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

    /** One per declared parameter, in declaration order. */
    private final List<ToolParameterSpec> parameters = new ArrayList<>();
}
