package io.github.synapse4j.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a tool the model may call: the signature becomes the declaration, the parameters
 * become the arguments, and the return becomes the result.
 *
 * <p>
 * Every attribute is a {@code String} defaulting to {@code ""}, and that is the configuration seam, not
 * an accident. An attribute supplies a default; an application may override it from its own
 * configuration; a completion step may resolve it dynamically — a SpEL expression, say. Whatever the
 * text resolves to is what configures the tool; text that resolves to blank means "nothing supplied",
 * and falls to the attribute's own rule, stated where it is declared.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolMethod {

    /**
     * The name the tool is known by; blank when nothing supplied one, and the tool then goes by the
     * method's own name.
     */
    String name() default "";

    /** What the tool does; blank when nothing supplied one, and the declaration then carries none. */
    String description() default "";

    /**
     * Whether the provider must enforce the input schema rather than merely aim at it.
     *
     * <p>
     * Blank leaves it to the protocol: {@code strict} is not sent, and whatever the endpoint does by
     * default stands. A written value that is not exactly {@code "false"} asks for enforcement.
     */
    String strict() default "";

    /**
     * The whole input schema as a JSON document, instead of the one put together from the parameters.
     *
     * <p>
     * It is {@link ToolParam#schema()} raised to the whole tool, and blank falls to the same rule: the
     * envelope is assembled from what each parameter settled on.
     */
    String schema() default "";

    /**
     * Which implementation builds this tool, where building one from the method alone would not do —
     * an implementation that fills a parameter from the conversation rather than from the model's
     * arguments, say.
     *
     * <p>
     * The value names a {@link Tool}, and is a name rather than a class: whoever turns this
     * annotation into a tool decides what the name means, and what an empty one means too.
     */
    String type() default "";
}
