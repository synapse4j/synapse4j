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
 * Every attribute is the value as written, blank meaning it was not written; see {@link ToolMethod}
 * for why the meaning of a blank is not decided here.
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
