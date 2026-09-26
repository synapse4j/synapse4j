package io.github.synapse4j.json;

import java.lang.reflect.Type;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A {@link JsonCodec} that leaves the binding itself to a subclass and takes care of this library's
 * own types in both directions — the ones whose document form is not the shape of their class.
 *
 * <p>
 * A subclass implements the hooks below and the parts of {@link JsonCodec} that only the JSON library
 * can supply: binding a value, and opening a writer or a reader. Extending this class is a
 * convenience, not a requirement: implementing {@link JsonCodec} directly is equally valid — those
 * types are then the implementer's to handle, and getting them wrong fails silently rather than
 * loudly, which is why this class exists.
 *
 * <p>
 * Nothing here is final. A subclass that has another type to treat specially can override
 * {@link #encode(Object)} or {@link #decode(String, Type)} and call {@code super} for what this class
 * already does.
 */
public abstract class AbstractJsonCodec implements JsonCodec {

    /**
     * {@inheritDoc}
     *
     * <p>
     * A {@link JsonSchema} is written as the document it describes; everything else is handed to
     * {@link #encodeValue(Object)} untouched.
     */
    @Override
    public String encode(@Nullable Object value) {
        return encodeValue(value instanceof JsonSchema schema ? schema.toMap() : value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Reading into {@link JsonSchema} — or a subclass of it, answered with a {@code JsonSchema} —
     * reads the document it describes. Anything else is handed to
     * {@link #decodeValue(String, Type)} untouched. The checks are on the type asked for rather
     * than on the value, so a {@code JsonSchema} nested in a collection is read as the
     * collection's element type would have it, not as a schema this method recognises.
     */
    @Override
    public <T> @Nullable T decode(String json, Type type) {
        if (type instanceof Class<?> asked && JsonSchema.class.isAssignableFrom(asked)) {
            return cast(JsonSchema.fromMap(decodeValue(json, Map.class)));
        }
        return decodeValue(json, type);
    }

    /**
     * Writes a value as JSON text, knowing nothing of this library's own types.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text; never {@code null}
     */
    protected abstract String encodeValue(@Nullable Object value);

    /**
     * Reads JSON text as a value of the given type, knowing nothing of this library's own types.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into; must not be {@code null}
     * @return the value; may be {@code null}
     */
    protected abstract <T> @Nullable T decodeValue(String json, Type type);

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

}
