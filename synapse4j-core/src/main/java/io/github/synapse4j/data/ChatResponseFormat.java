package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * The shape the answer should take: prose, any JSON, or JSON that conforms to a given schema.
 *
 * <p>
 * The mode and the schema are one type rather than loose fields on the request, because they are one
 * requirement: a request either asks for a shape or it does not.
 *
 * <p>
 * The schema is JSON Schema text, for the same reason a tool's argument schema is: this library does
 * not bind a JSON library, so the schema is produced by the codec the application chose.
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
public class ChatResponseFormat {

    /** Answer in prose. The usual default, worth naming to override a default explicitly. */
    public static final String TYPE_TEXT = "text";

    /** Answer with valid JSON, without constraining its shape. */
    public static final String TYPE_JSON = "json";

    /** Answer with JSON that conforms to the schema set on this format. */
    public static final String TYPE_JSON_SCHEMA = "json_schema";

    /** One of the {@code TYPE_*} constants, or any other value a provider understands. */
    private @Nullable String type;

    /** Name of the schema; the protocol that requires a name needs one. */
    private @Nullable String name;

    /** What the schema describes, for the model to read. */
    private @Nullable String description;

    /** The schema, as JSON Schema text. */
    private @Nullable String schema;

    /**
     * Whether the provider has to enforce the schema rather than merely aim at it; {@code null}
     * leaves the decision to the protocol's default. Only the protocols that carry the flag inside
     * a schema-shaped answer send it, and only for {@link #TYPE_JSON_SCHEMA}.
     */
    private @Nullable Boolean strict;

    /** Provider-specific fields of this requirement. */
    private final ProviderExtras extras = new ProviderExtras();

}
