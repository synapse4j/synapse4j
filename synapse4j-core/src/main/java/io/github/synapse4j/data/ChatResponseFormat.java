package io.github.synapse4j.data;

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
 * Properties of the requirement that only some protocols have — the flag that turns enforcement on,
 * where enforcement is optional — go in {@link #getExtras()}, like every other provider-specific
 * field.
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
    private String type;

    /** Name of the schema; the protocol that requires a name needs one. */
    private String name;

    /** What the schema describes, for the model to read. */
    private String description;

    /** The schema, as JSON Schema text. */
    private String schema;

    /** Provider-specific fields of this requirement. */
    private final ProviderExtras extras = new ProviderExtras();

}
