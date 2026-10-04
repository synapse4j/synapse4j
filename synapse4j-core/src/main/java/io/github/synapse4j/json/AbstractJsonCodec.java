package io.github.synapse4j.json;

import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;

/**
 * A {@link JsonCodec} that leaves the binding to a subclass: {@link #encode(Object)} and
 * {@link #decode(String, Type)} are the hooks below, and the subclass supplies what only the JSON
 * library can — binding a value, and opening a writer or a reader.
 *
 * <p>
 * Extending this class is a convenience, not a requirement: implementing {@link JsonCodec} directly is
 * equally valid.
 *
 * <p>
 * Nothing here is final. A subclass that has a type to treat specially can override
 * {@link #encode(Object)} or {@link #decode(String, Type)} and call {@code super} for the rest.
 */
public abstract class AbstractJsonCodec implements JsonCodec {

    @Override
    public String encode(@Nullable Object value) {
        return encodeValue(value);
    }

    @Override
    public <T> @Nullable T decode(String json, Type type) {
        return decodeValue(json, type);
    }

    /**
     * Writes a value as JSON text.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text; never {@code null}
     */
    protected abstract String encodeValue(@Nullable Object value);

    /**
     * Reads JSON text as a value of the given type.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into; must not be {@code null}
     * @return the value; may be {@code null}
     */
    protected abstract <T> @Nullable T decodeValue(String json, Type type);

}
