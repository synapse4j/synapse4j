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
 * Every attribute holds text, not a value, and deliberately so: the text is read by whoever completes
 * the tool, which may resolve it rather than take it literally — a value looked up in configuration, or
 * an expression such as a SpEL one. An empty string is the one form that means nothing was written;
 * what an empty value becomes is the completion step's to decide, not this annotation's.
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
