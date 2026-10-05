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
 * <li>{@code fromModel} blank is judged from the parameter itself: a {@link
 * io.github.synapse4j.data.ChatContext}-typed parameter is filled from the conversation rather than
 * produced by the model, and anything else is declared to the model. A written value overrides
 * that — the built-in completion reads the parameter as off the wire only when it is exactly
 * {@code "false"}.</li>
 * <li>{@code schema} blank is whatever the codec derives from the parameter's own type. A written
 * value is the schema of this one property instead, as the JSON document a codec reads — a contract
 * the codec has to be able to bind, since the declaration is what the model is held to.</li>
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

    /** Whether the model produces it, as against its value coming from somewhere else. */
    String fromModel() default "";

    /** The schema of this one property, as a JSON document, instead of the one derived from the type. */
    String schema() default "";
}
