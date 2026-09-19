package io.github.synapse4j.schema;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * A {@link JsonCodec} that leaves the binding itself to a subclass, and takes care of the types of
 * this library whose document form is not the shape of their class.
 *
 * <p>
 * Today that is one type: {@link JsonSchema}. Written as the class it is, a schema would come out with
 * the wrong keywords — {@code enumValues} instead of {@code enum}, {@code defs} instead of
 * {@code $defs}, an {@code extras} object standing where its keywords belong — and nothing would fail
 * loudly: the document would parse, be sent, and mean something else. {@link JsonSchema#toMap()} is
 * its document form, so that is what gets written.
 *
 * <p>
 * On the way in, {@link JsonView} gets its own branch: the document is decoded generically (any
 * root) and wrapped, so callers navigate provider responses without each codec re-implementing the
 * wrapping.
 *
 * <p>
 * A subclass implements the two hooks below and the two schema generators of {@link JsonCodec}.
 * Extending this class is a convenience, not a requirement: implementing {@link JsonCodec} directly is
 * equally valid — the special case above is then the implementer's to repeat, and an implementation
 * that gets it wrong fails silently, which is why this class exists.
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
    public String encode(Object value) {
        return encodeValue(value instanceof JsonSchema schema ? schema.toMap() : value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Reading into {@link JsonSchema} reads the document it describes; reading into {@link JsonView}
     * reads the document generically (any root — object, array, or scalar) and hands the caller a
     * null-safe view over it. Anything else is handed to {@link #decodeValue(String, Type)}
     * untouched. The checks are on the type asked for rather than on the value, so a
     * {@code JsonSchema} nested in a collection is read as the collection's element type would have
     * it, not as a schema this method recognises. {@code JsonView} is a read-side type; passing one
     * to {@link #encode(Object)} is not supported.
     */
    @Override
    public <T> T decode(String json, Type type) {
        if (JsonSchema.class.equals(type)) {
            return cast(JsonSchema.fromMap(decodeValue(json, Map.class)));
        }
        if (JsonView.class.equals(type)) {
            return cast(JsonView.of(decodeValue(json, Object.class)));
        }
        return decodeValue(json, type);
    }

    /**
     * Writes a value as JSON text, knowing nothing of this library's own types.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text; never {@code null}
     */
    protected abstract String encodeValue(Object value);

    /**
     * Reads JSON text as a value of the given type, knowing nothing of this library's own types.
     *
     * @param <T>  the type of the value
     * @param json the JSON text to read; must not be {@code null}
     * @param type the type to read into; must not be {@code null}
     * @return the value; may be {@code null}
     */
    protected abstract <T> T decodeValue(String json, Type type);

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

}
