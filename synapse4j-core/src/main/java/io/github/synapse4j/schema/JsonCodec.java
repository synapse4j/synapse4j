package io.github.synapse4j.schema;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Writes Java values as JSON text, reads JSON text back into Java values, and derives the
 * {@link JsonSchema} that says what shape that JSON has to have.
 *
 * <p>
 * Schema generation and binding live behind one interface on purpose. They have to agree: a schema
 * that describes a shape the binder does not produce is worse than no schema at all, and letting an
 * application mix a generator from one JSON ecosystem with a binder from another is exactly how that
 * happens. One implementation, one answer.
 *
 * <p>
 * No JSON library appears in these signatures — only {@link Object}, {@link String}, {@link Type} and
 * this library's own {@link JsonSchema}. An implementation is where the dependency on Jackson, Gson
 * or whatever else belongs, and an application picks the implementation it wants by instantiating it.
 * Implementations are stateless and therefore shareable between threads.
 *
 * <p>
 * {@link #generateSchema(Type)} takes a {@link Type} rather than a {@link Class} so that a generic type
 * arrives with its type arguments — {@code List<Order>} has to describe an array of orders, not an
 * array of anything.
 *
 * <p>
 * A schema and its JSON text are bridged through {@link JsonSchema#toMap()} and
 * {@link JsonSchema#fromMap(Map)} in the default methods below rather than through the codec's own
 * object binding. Keyword names are fixed by the JSON Schema specification, so they must not travel
 * through a codec's naming strategy: a codec configured for {@code snake_case} would silently turn
 * {@code additionalProperties} into {@code additional_properties} and the schema would stop meaning
 * anything. An implementation may override these two methods when it can do better directly, but it
 * then owes the same keyword names.
 */
public interface JsonCodec {

    /**
     * Generates the schema that describes values of the given type.
     *
     * @param type the type to describe, type arguments included; must not be {@code null}
     * @return the schema; never {@code null}
     */
    JsonSchema generateSchema(Type type);

    /**
     * Writes a value as JSON text.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text; never {@code null}
     */
    String encode(Object value);

    /**
     * Reads JSON text into a value of the given type.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into, type arguments included; must not be {@code null}
     * @return the value; may be {@code null}
     */
    <T> T decode(String json, Type type);

    /**
     * Writes a schema as JSON text.
     *
     * @param schema the schema to write; must not be {@code null}
     * @return the JSON text; never {@code null}
     */
    default String encodeSchema(JsonSchema schema) {
        return encode(schema.toMap());
    }

    /**
     * Reads a schema from JSON text.
     *
     * @param json the JSON text to read; must not be {@code null}
     * @return the schema; never {@code null}
     */
    @SuppressWarnings("unchecked")
    default JsonSchema decodeSchema(String json) {
        return JsonSchema.fromMap((Map<String, Object>) decode(json, Map.class));
    }

}
