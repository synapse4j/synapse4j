package io.github.synapse4j.json;

import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.data.ProviderExtras;
import org.jspecify.annotations.Nullable;

/**
 * A {@link JsonWriter} that adds the writing of a whole value over the tokens, leaving the tokens
 * themselves to a subclass.
 *
 * <p>
 * A subclass implements the tokens and the one thing only the JSON library can supply: serializing a
 * value none of the shapes above covers. {@link #writeValue(Object)} is then how a value of any shape
 * reaches the document — the shapes a JSON document is made of are written token by token, the types
 * this library owns as the documents they describe, and anything else is handed to the library.
 * Extending this class is a convenience, not a requirement: implementing {@link JsonWriter} directly
 * is equally valid — writing values is then the implementer's to do, and getting it wrong fails
 * silently rather than loudly, which is why this class exists.
 *
 * <p>
 * Nothing here is final. A subclass whose library writes a whole value in one call can override
 * {@link #writeValue(Object)} outright.
 */
public abstract class AbstractJsonWriter implements JsonWriter {

    /**
     * {@inheritDoc}
     *
     * <p>
     * Written over the tokens of {@link JsonWriter}: a map becomes an object and a list an array, and
     * a {@link Reader} as the string it yields, so a payload larger than memory still goes out, and
     * their members and elements come back through this method, so a type this library owns is
     * recognized wherever it sits rather than only at the top.
     */
    @Override
    public JsonWriter writeValue(@Nullable Object value) {
        if (value == null) {
            return writeNull();
        }
        if (value instanceof String text) {
            return writeString(text);
        }
        if (value instanceof Reader text) {
            return writeString(text);
        }
        if (value instanceof Boolean flag) {
            return writeBoolean(flag);
        }
        if (value instanceof Number number) {
            return writeNumber(number);
        }
        if (value instanceof Map<?, ?> members) {
            writeStartObject();
            for (Map.Entry<?, ?> member : members.entrySet()) {
                writeName(String.valueOf(member.getKey()));
                writeValue(member.getValue());
            }
            return writeEndObject();
        }
        if (value instanceof List<?> elements) {
            writeStartArray();
            for (Object element : elements) {
                writeValue(element);
            }
            return writeEndArray();
        }
        if (value instanceof JsonSchema schema) {
            return writeValue(schema.toMap());
        }
        if (value instanceof ProviderExtras extras) {
            return writeValue(extras.nestedMap());
        }
        return writeValueDirect(value);
    }

    /**
     * Writes a value none of the shapes above covers, with the JSON library this writer came from.
     *
     * @param value the value; never {@code null}
     * @return this writer
     */
    protected abstract JsonWriter writeValueDirect(Object value);

    /**
     * Writes a number as the narrowest JSON number that holds it exactly: a decimal goes out through
     * {@link #writeNumber(BigDecimal)} rather than a {@code double}, which would round it, and an
     * integer too large for a {@code long} goes out as a decimal rather than being truncated.
     */
    private JsonWriter writeNumber(Number number) {
        if (number instanceof Double || number instanceof Float) {
            return writeNumber(number.doubleValue());
        }
        if (number instanceof BigDecimal decimal) {
            return writeNumber(decimal);
        }
        if (number instanceof BigInteger integer) {
            return writeNumber(new BigDecimal(integer));
        }
        return writeNumber(number.longValue());
    }

}
