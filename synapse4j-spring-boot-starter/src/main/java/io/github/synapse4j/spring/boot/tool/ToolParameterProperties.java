package io.github.synapse4j.spring.boot.tool;

import org.jspecify.annotations.Nullable;

import lombok.Data;

/**
 * One parameter's overrides, keyed in {@link ToolMethodProperties#getParameters()} by the name the
 * parameter carries before this configuration applies.
 *
 * <p>
 * Every attribute mirrors the {@code @ToolParam} one it stands for and is read the way
 * {@link ToolMethodProperties} describes: left out, the annotation's value stands; written, even
 * empty, it takes its place.
 */
@Data
public class ToolParameterProperties {

    /** The property name this argument goes by; {@code @ToolParam#name()}. */
    private @Nullable String name;

    /** What this argument means; {@code @ToolParam#description()}. */
    private @Nullable String description;

    /** Whether the model has to produce it; {@code @ToolParam#required()}. */
    private @Nullable String required;

    /** Whether the model produces it at all; {@code @ToolParam#fromModel()}. */
    private @Nullable String fromModel;

    /** The schema of this one property as a JSON document; {@code @ToolParam#schema()}. */
    private @Nullable String schema;

}
