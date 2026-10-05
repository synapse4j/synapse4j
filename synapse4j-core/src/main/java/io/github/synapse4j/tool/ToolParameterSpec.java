package io.github.synapse4j.tool;

import java.lang.reflect.Parameter;

import io.github.synapse4j.json.JsonSchema;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One declared parameter of a tool method, read: the Java side it was read from, and the values its
 * annotations wrote.
 *
 * <p>
 * A customizer sees and changes this the way it does {@link ToolMethodSpec}; see there for what that
 * means.
 *
 * <p>
 * The two schema answers are deliberately different in kind. {@link #schema} is the configuration
 * surface: text, written by an annotation or by a customizer, blank when nobody supplied one. {@link
 * #resolvedSchema} is the conclusion: the schema of this one property as the declaration carries it,
 * which {@link MethodTools} settles and {@link MethodTool} reads. A customizer holding a
 * {@link JsonSchema} in hand writes the second and skips the round trip through text the first would
 * need.
 */
@Getter
@Setter
@ToString(exclude = "valueProvider")
@RequiredArgsConstructor
public class ToolParameterSpec {

    /** The declared parameter the values were read from. */
    @NonNull
    private final Parameter parameter;

    /** The property name this argument goes by; blank when nothing supplied one. */
    @NonNull
    private String name = "";

    /** What this argument means; blank when nothing supplied it. */
    @NonNull
    private String description = "";

    /** Whether the model has to produce it; blank when nothing supplied it. */
    @NonNull
    private String required = "";

    /** Whether the model produces it, as against its value coming from somewhere else. */
    @NonNull
    private String fromModel = "";

    /** The schema of this one property as a JSON document, instead of the one derived from the type. */
    @NonNull
    private String schema = "";

    /**
     * Where this argument's value comes from when the model does not produce it; {@code null} when
     * nothing supplies one. Written by a {@link ToolMethodSpecCustomizer}, which is how an application
     * fills an argument that is not the model's to answer — a principal, a locale, a bean.
     */
    private @Nullable ToolParameterValueProvider valueProvider;

    /**
     * The schema of this one property as the declaration carries it: {@code null} while nothing has
     * settled it, and for a parameter that never reaches the model. {@link MethodTools} writes it and
     * {@link MethodTool} reads it — it is how the arguments schema is put together.
     */
    private @Nullable JsonSchema resolvedSchema;

    /**
     * Whether the model has to produce this argument, as {@link #required} states it. Written as text
     * because it has three states, and only this answers with a decision; a value that is not exactly
     * {@code "false"} says yes, so one nobody wrote is read as required.
     *
     * @return whether the model has to produce this argument
     */
    public boolean isRequired() {
        return !"false".equals(required);
    }

    /**
     * Whether the model produces this argument, as {@link #fromModel} states it, under the same rule:
     * a value that is not exactly {@code "false"} says yes.
     *
     * @return whether the value comes from the model's arguments
     */
    public boolean fromModel() {
        return !"false".equals(fromModel);
    }
}