package io.github.synapse4j.tool;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.json.JsonSchema;
import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A tool the model may call: what it is called, what it does, and what arguments it takes.
 *
 * <p>
 * The arguments schema is a {@link JsonSchema} rather than text, because a schema is a value this
 * library already models: the codec the application chose produces one, and a protocol that has to
 * reshape the schema for the wire can read and rewrite it rather than parse a string first.
 *
 * <p>
 * A tool carries its own {@link ProviderExtras} bag, like every other node. Where the adapter merges
 * that bag is part of the adapter's contract; it is the only way to reach a provider field that the
 * wire nests deeper than the fields modelled here.
 *
 * <p>
 * Built-in tools — a search or a code interpreter the provider runs itself — have a different shape
 * and no argument schema, so they are not this type. When they are modelled they belong in a
 * subclass, which is why this class stays open.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ToolDefinition {

    /** Name the model calls the tool by; the application resolves it against its own registry. */
    private @Nullable String name;

    /** What the tool does, which is how the model decides whether to call it. */
    private @Nullable String description;

    /** The arguments schema. */
    private @Nullable JsonSchema inputSchema;

    /**
     * Whether the provider has to fill in an invocation that enforces this schema rather than
     * merely aiming at it; {@code null} leaves the decision to the protocol's default.
     */
    private @Nullable Boolean strict;

    /** Provider-specific fields to merge into this tool when the request is sent. */
    private final ProviderExtras extras = new ProviderExtras();

    /**
     * A tool with the given name, purpose and argument schema; nothing is said about enforcement,
     * see {@code getStrict()}.
     *
     * @param name        the name the model calls the tool by, {@code null} when unnamed
     * @param description what the tool does, {@code null} when none
     * @param inputSchema the arguments schema
     */
    public ToolDefinition(@Nullable String name, @Nullable String description, JsonSchema inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

}
