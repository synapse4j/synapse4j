package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.json.JsonSchema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The shape the answer should take: prose, any JSON, or JSON that conforms to a given schema.
 *
 * <p>
 * The mode and the schema are one type rather than loose fields in the options, because they are one
 * requirement: a request either asks for a shape or it does not.
 *
 * <p>
 * The schema is a {@link JsonSchema}, like a tool's argument schema: the codec the application chose
 * produces one, and a protocol that has to reshape it for the wire can read and rewrite it rather
 * than parse a string first.
 *
 * <p>
 * A protocol that cannot express a mode has to fail loudly. Answering in prose when a shape was
 * asked for is the most expensive way to be wrong, because it looks like success.
 *
 * <p>
 * Properties of the requirement that only some protocols have go in {@code getExtras()}, like
 * every other provider-specific field. The flag that turns enforcement on is modelled rather than
 * left to the bag: every major protocol carries it in some form, so {@code getStrict()} speaks for
 * all of them and each adapter translates it.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ChatResponseFormat implements Effective<ChatResponseFormat> {

    /** Answer in prose. The usual default, worth naming to override a default explicitly. */
    public static final String TYPE_TEXT = "text";

    /** Answer with valid JSON, without constraining its shape. */
    public static final String TYPE_JSON = "json";

    /** Answer with JSON that conforms to the schema set on this format. */
    public static final String TYPE_JSON_SCHEMA = "json_schema";

    /**
     * One of the {@code TYPE_*} constants, or any other value a provider understands. The value is
     * open because a protocol may add a shape this library has not heard of; a protocol that
     * expresses only fixed shapes fails the call on one it cannot honour rather than sending a
     * request that would come back as something else.
     */
    private @Nullable String type;

    /** Name of the schema; the protocol that requires a name needs one. */
    private @Nullable String name;

    /** What the schema describes, for the model to read. */
    private @Nullable String description;

    /** The schema. */
    private @Nullable JsonSchema schema;

    /**
     * Whether the provider has to enforce the schema rather than merely aim at it; {@code null}
     * leaves the decision to the protocol's default. Only the protocols that carry the flag inside
     * a schema-shaped answer send it, and only for {@link #TYPE_JSON_SCHEMA}. A protocol that cannot
     * switch enforcement off enforces anyway, so {@code false} there still gets a schema-shaped
     * answer — stricter than asked for, never looser.
     */
    private @Nullable Boolean strict;

    /** Provider-specific fields of this requirement. */
    private final ProviderExtras extras = new ProviderExtras();

    public ChatResponseFormat(ChatResponseFormat other) {
        this.type = other.type;
        this.name = other.name;
        this.description = other.description;
        this.schema = other.schema;
        this.strict = other.strict;
        this.extras.putAll(other.extras);
    }

    @Override
    public ChatResponseFormat copy() {
        return new ChatResponseFormat(this);
    }

    @Override
    public void fillFrom(ChatResponseFormat other) {
        if (type == null) {
            type = other.type;
        }
        if (name == null) {
            name = other.name;
        }
        if (description == null) {
            description = other.description;
        }
        if (schema == null) {
            schema = other.schema;
        }
        if (strict == null) {
            strict = other.strict;
        }
        extras.fillFrom(other.extras);
    }

}
