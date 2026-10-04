package io.github.synapse4j.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The metadata of one parameter a tool method takes from the model: the property it goes by, what it
 * means, and whether the model has to produce it.
 *
 * <p>
 * Every attribute holds text, not a value, for the reason {@link ToolMethod} gives: the completion step
 * reads it, so it may be a literal or something to resolve. A blank string means the attribute was not
 * written. The built-in completion reads {@code required} as text as well — an argument is optional
 * only when it is exactly {@code "false"}, and required otherwise, blank included — and a completion
 * step of the application's own may read it another way.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Documented
public @interface ToolParam {

    /** The property name this argument goes by. */
    String name() default "";

    /** What this argument means. */
    String description() default "";

    /** Whether the model has to produce it. */
    String required() default "";
}
