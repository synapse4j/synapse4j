package io.github.synapse4j.tool;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.json.JsonSchema;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * A tool the model may call: what it is called, what it does, and what arguments it takes.
 *
 * <p>
 * A declaration is read-only: its own fields cannot change after it is built, so it is safe to hand to
 * a tool and to share across concurrent calls. The extras it carries are frozen shallowly, so a value
 * stored there that the caller still holds and later changes is seen through the declaration too — such
 * a value is the caller's to keep still.
 *
 * <p>
 * Where the adapter merges a tool's {@link ProviderExtras} bag is part of the adapter's contract;
 * it is the only way to reach a provider field that the wire nests deeper than the fields modelled
 * here.
 *
 * <p>
 * Built-in tools — a search or a code interpreter the provider runs itself — have a different shape
 * and no argument schema, so they are not this type. When they are modelled they belong in a
 * subclass, which is why this class stays open.
 */
@Getter
@ToString
public class ToolDefinition {

    /** Name the model calls the tool by; the application resolves it against its own registry. */
    private final String name;

    /** What the tool does, which is how the model decides whether to call it. */
    private final @Nullable String description;

    /** The arguments schema, {@code null} when the tool takes none. */
    private final @Nullable JsonSchema inputSchema;

    /**
     * Whether the provider has to fill in an invocation that enforces this schema rather than
     * merely aiming at it; {@code null} leaves the decision to the protocol's default.
     */
    private final @Nullable Boolean strict;

    /** Provider-specific fields to merge into this tool when the request is sent. */
    private final ProviderExtras extras;

    /**
     * A tool with the given name, purpose and argument schema.
     *
     * @param name        the name the model calls the tool by; must not be {@code null}
     * @param description what the tool does, {@code null} when none
     * @param inputSchema the arguments schema, {@code null} when the tool takes none
     */
    public ToolDefinition(String name, @Nullable String description, @Nullable JsonSchema inputSchema) {
        this(name, description, inputSchema, null, null);
    }

    /**
     * A tool with everything it can carry.
     *
     * @param name        the name the model calls the tool by; must not be {@code null}
     * @param description what the tool does, {@code null} when none
     * @param inputSchema the arguments schema, {@code null} when the tool takes none
     * @param strict      whether the provider must enforce the schema, {@code null} for the
     *                        protocol's default
     * @param extras      provider-specific fields, {@code null} for none
     */
    public ToolDefinition(@NonNull String name, @Nullable String description, @Nullable JsonSchema inputSchema,
            @Nullable Boolean strict, @Nullable ProviderExtras extras) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.strict = strict;
        this.extras = extras == null ? new ProviderExtras().freeze() : extras.freeze();
    }

}
