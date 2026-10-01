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
 * Every attribute is optional and holds the text exactly as written; empty means nothing was written.
 * What an empty value becomes is not decided here — that belongs to whatever turns this annotation
 * into a tool, which may also supply a value from somewhere else, such as configuration.
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
     * The value names an {@link AnnotatedTool}, and is a name rather than a class: whoever turns this
     * annotation into a tool decides what the name means, and what an empty one means too.
     */
    String type() default "";
}
