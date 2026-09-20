package io.github.synapse4j.json;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link JsonReader} that adds the operations this library can read over the primitives, leaving
 * the primitives themselves to a subclass.
 *
 * <p>
 * A subclass implements the tokens and the accessors that only the JSON library can supply, and gets
 * {@link #captureValue()} from here. Extending this class is a convenience, not a requirement:
 * implementing {@link JsonReader} directly is equally valid — capturing is then the implementer's to
 * read, and getting it wrong fails silently rather than loudly, which is why this class exists.
 *
 * <p>
 * Nothing here is final. A subclass whose library hands back a whole value in one call can override
 * {@link #captureValue()} outright.
 */
public abstract class AbstractJsonReader implements JsonReader {

    /**
     * {@inheritDoc}
     *
     * <p>
     * Read over the primitives of {@link JsonReader}: the token says what kind of value is here, and
     * the accessors say what is in it.
     */
    @Override
    public Object captureValue() {
        Token current = token();
        if (current == null) {
            throw new IllegalStateException("captureValue() needs a value, but the reader has not advanced");
        }
        return switch (current) {
            case START_OBJECT -> captureObject();
            case START_ARRAY -> captureArray();
            case STRING -> string();
            case NUMBER -> captureNumber(string());
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NULL -> null;
            default -> throw new IllegalStateException(
                    "captureValue() is only available on a value, but the current token is " + current);
        };
    }

    /** Reads the body of the object the reader has just entered, keys in document order. */
    private Object captureObject() {
        Map<String, Object> captured = new LinkedHashMap<>();
        while (nextToken() != Token.END_OBJECT) {
            String field = name();
            nextToken();
            captured.put(field, captureValue());
        }
        return captured;
    }

    /** Reads the elements of the array the reader has just entered. */
    private Object captureArray() {
        List<Object> captured = new ArrayList<>();
        while (nextToken() != Token.END_ARRAY) {
            captured.add(captureValue());
        }
        return captured;
    }

    /**
     * Turns a number's text into the number it denotes, keeping whole numbers whole.
     *
     * <p>
     * Whole numbers become a {@code Long}, or a {@link BigInteger} when they do not fit — a number
     * this library never read into a field of its own is kept as the document spelled it rather than
     * rounded into a {@code double}. Everything else becomes a {@code Double}.
     */
    private static Object captureNumber(String text) {
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException doesNotFit) {
                return new BigInteger(text);
            }
        }
        return Double.valueOf(text);
    }

}
