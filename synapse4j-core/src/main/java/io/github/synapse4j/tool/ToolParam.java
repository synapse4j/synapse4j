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
 * Every attribute is a {@code String} defaulting to {@code ""}, for the reason {@link ToolMethod} gives:
 * the text is resolved — an annotation default, an application's configuration, a dynamic expression —
 * and what it resolves to is what configures the argument; text that resolves to blank falls to the
 * field's own default:
 *
 * <ul>
 * <li>{@code name} blank becomes the parameter's own name;</li>
 * <li>{@code description} blank becomes no description;</li>
 * <li>{@code required} blank is judged from the parameter itself: a value the type makes optional (an
 * {@code Optional}, say) is not required, and anything else is. A written value overrides that — the
 * built-in completion reads the argument as optional only when it is exactly {@code "false"}.</li>
 * </ul>
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
