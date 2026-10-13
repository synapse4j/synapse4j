package io.github.synapse4j.tool;

import java.lang.reflect.Parameter;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.json.JsonSchema;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One declared parameter of a tool method while its declaration is being settled: the Java parameter
 * it was read from, and the strings a step has written so far. Every value lives here as a string, so
 * a blank one says nobody supplied it.
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

    /**
     * The schema of this one property as a JSON document, instead of the one derived from the type:
     * the configuration surface, blank when nobody supplied one.
     */
    @NonNull
    private String schema = "";

    /**
     * Where this argument's value comes from when the model does not produce it; {@code null} when
     * nothing supplies one. A step writes it to fill an argument that is not the model's to answer — a
     * principal, a locale, a bean.
     */
    private @Nullable ToolParameterValueProvider valueProvider;

    /**
     * The schema of this one property as the declaration carries it: {@code null} while nothing has
     * settled it, and for a parameter the model does not produce. It is filled from {@link #schema},
     * or written directly by a step holding a {@link JsonSchema} in hand, so a schema already in hand
     * skips the round trip through text.
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