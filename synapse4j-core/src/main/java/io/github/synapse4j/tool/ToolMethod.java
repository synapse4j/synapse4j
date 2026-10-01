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
 * Every attribute is the value as written, blank meaning it was not written. What a blank becomes is
 * decided by the resolution that follows rather than here, which is what lets a later layer supply
 * it instead — a description read from configuration, say.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolMethod {

    /** The name the tool is known by. */
    String name() default "";

    /** What the tool does. */
    String description() default "";
}
