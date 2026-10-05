package io.github.synapse4j.spring.boot.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Data;

/**
 * One tool's overrides, keyed in {@link ToolsProperties#getMethods()} by the name the tool carries
 * before this configuration applies.
 *
 * <p>
 * Every attribute mirrors the {@code @ToolMethod} one it stands for and is read the same way: left
 * out, it leaves the annotation's own value standing; written — an empty value too — it takes that
 * value's place, and an empty one falls to the default rule that would have applied had the annotation
 * written nothing. So a description written here replaces the annotation's, and an empty one clears it.
 */
@Data
public class ToolMethodProperties {

    /** The name the model calls the tool by; {@code @ToolMethod#name()}. */
    private @Nullable String name;

    /** What the tool does; {@code @ToolMethod#description()}. */
    private @Nullable String description;

    /** Which implementation builds this tool; {@code @ToolMethod#type()}. */
    private @Nullable String type;

    /** The whole input schema as a JSON document; {@code @ToolMethod#schema()}. */
    private @Nullable String schema;

    /**
     * Whether the provider must enforce the schema; {@code @ToolMethod#strict()}. Written, it replaces
     * the annotation's; unset, the annotation's stands, and where that too states none it falls to
     * {@link ToolsProperties#getStrict()} and then to the protocol.
     */
    private @Nullable String strict;

    /**
     * Provider-specific fields to merge into the tool declaration, by raw wire name — the form
     * {@link io.github.synapse4j.data.ProviderExtras#putRaw(String, Object)} takes. A dotted key
     * addresses a nested member; a value takes its type from the configuration source, so a non-string
     * extra belongs in YAML.
     */
    private final Map<String, Object> extras = new LinkedHashMap<>();

    /**
     * One parameter's overrides, keyed by the name the parameter carries before this configuration
     * applies.
     */
    private final Map<String, ToolParameterProperties> parameters = new LinkedHashMap<>();

}
