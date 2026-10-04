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
 * and falls to the field's own default rule. Each field defines its own, where it is read:
 *
 * <ul>
 * <li>{@code name} blank becomes the method's own name;</li>
 * <li>{@code description} blank becomes no description;</li>
 * <li>{@code type} blank becomes the implementation the reading uses by default.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolMethod {

    /** The name the tool is known by. */
    String name() default "";

    /** What the tool does. */
    String description() default "";

    /**
     * Which implementation builds this tool, where building one from the method alone would not do —
     * an implementation that fills a parameter from the conversation rather than from the model's
     * arguments, say.
     *
     * <p>
     * The value names a {@link SpecTool}, and is a name rather than a class: whoever turns this
     * annotation into a tool decides what the name means, and what an empty one means too.
     */
    String type() default "";
}
