package io.github.synapse4j.jackson;

import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.function.Supplier;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;
import io.github.synapse4j.json.AbstractJsonReader;
import io.github.synapse4j.json.JsonReader;
import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;

/**
 * A {@link JsonReader} that reads through a Jackson {@link JsonParser}.
 *
 * <p>
 * Internal to this module: an application gets one from {@link JacksonJsonCodec#reader} and never
 * names this class. The parser comes from a factory that leaves the source alone, so {@link #close()}
 * releases Jackson's buffers and nothing else.
 *
 * <p>
 * Jackson is asked for a value only when the current token can carry it, and the accessors answer
 * from the token rather than from Jackson's parser context: left alone, Jackson coerces where
 * {@link JsonReader} says the caller is wrong — {@code getLongValue()} truncates a float token, and
 * {@code getString()} answers {@code "{"} for the start of an object. So the token is checked here,
 * and the caller gets the {@link IllegalStateException} the interface promises instead of a plausible
 * wrong answer.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class JacksonJsonReader extends AbstractJsonReader {

    private final JsonParser parser;

    /**
     * The token the last {@link #nextToken()} answered with, which is what {@link #token()} returns.
     * Kept here rather than asked of the parser, because Jackson reports the same {@code null} both
     * before a document starts and after it has ended.
     */
    private @Nullable Token current;

    /**
     * Opens a reader over the given source.
     *
     * <p>
     * Creating a parser reads the source: Jackson detects the encoding from its first bytes. So a
     * source that has already failed fails here rather than on the first token, and that failure is
     * translated too — otherwise the caller would have to catch a Jackson exception from a method
     * that promises this library's own.
     */
    static JacksonJsonReader open(JsonFactory factory, InputStream in) {
        return translate("Opening the JSON reader failed",
                () -> new JacksonJsonReader(factory.createParser(ObjectReadContext.empty(), in)));
    }

    @Override
    public Token nextToken() {
        this.current = toToken(read(parser::nextToken));
        return current;
    }

    @Override
    public @Nullable Token token() {
        return current;
    }

    @Override
    public @Nullable String name() {
        return parser.currentToken() == JsonToken.PROPERTY_NAME ? parser.currentName() : null;
    }

    @Override
    public @Nullable String string() {
        JsonToken current = parser.currentToken();
        if (current == JsonToken.VALUE_STRING || current == JsonToken.VALUE_NUMBER_INT
                || current == JsonToken.VALUE_NUMBER_FLOAT) {
            return read(parser::getString);
        }
        return null;
    }

    @Override
    public long longValue() {
        require("longValue()", JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT);
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return read(parser::getLongValue);
        }
        // A number spelled with a fraction can still be whole — "11.0" is the number eleven — so it
        // is read exactly when it is, and refused as a failed read, not as a misuse, when it is not.
        String text = read(parser::getString);
        try {
            return new BigDecimal(text).longValueExact();
        } catch (ArithmeticException notWhole) {
            throw new SynapseException("the number is not an integer: " + text);
        }
    }

    @Override
    public double doubleValue() {
        require("doubleValue()", JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT);
        return read(parser::getDoubleValue);
    }

    @Override
    public boolean booleanValue() {
        require("booleanValue()", JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE);
        return read(parser::getBooleanValue);
    }

    @Override
    public void skipValue() {
        run(parser::skipChildren);
    }

    @Override
    public long string(Writer out) {
        require("string(Writer)", JsonToken.VALUE_STRING);
        return read(() -> parser.readString(out));
    }

    @Override
    public void close() {
        run(parser::close);
    }

    /**
     * Maps Jackson's tokens onto the ones this library publishes. The unmapped kinds are the ones no
     * reader of a document can be handed: a value with no textual form, and "no token".
     */
    private static Token toToken(@Nullable JsonToken token) {
        if (token == null) {
            return Token.END_DOCUMENT;
        }
        return switch (token) {
            case START_OBJECT -> Token.START_OBJECT;
            case END_OBJECT -> Token.END_OBJECT;
            case START_ARRAY -> Token.START_ARRAY;
            case END_ARRAY -> Token.END_ARRAY;
            case PROPERTY_NAME -> Token.NAME;
            case VALUE_STRING -> Token.STRING;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> Token.NUMBER;
            case VALUE_TRUE -> Token.TRUE;
            case VALUE_FALSE -> Token.FALSE;
            case VALUE_NULL -> Token.NULL;
            default -> throw new SynapseException("unsupported JSON token: " + token);
        };
    }

    /**
     * Fails before Jackson is asked for a value the current token cannot carry, where Jackson would
     * answer with a coerced or truncated one.
     */
    private void require(String operation, JsonToken first, JsonToken... rest) {
        JsonToken current = parser.currentToken();
        if (current == first) {
            return;
        }
        for (JsonToken token : rest) {
            if (current == token) {
                return;
            }
        }
        throw new IllegalStateException(operation + " is only available on " + first + " or "
                + Arrays.toString(rest) + ", but the current token is " + current);
    }

    /** Reads one value, translating what Jackson reports into what this library reports. */
    private <T> @Nullable T read(Supplier<T> read) {
        return translate("Reading the JSON document failed", read);
    }

    /** Performs one read whose result is not handed back, with the same translation as {@link #read}. */
    private void run(Runnable read) {
        this.read(() -> {
            read.run();
            return null;
        });
    }

    /** Runs one read, translating what Jackson reports into what this library reports. */
    private static <T> @Nullable T translate(String message, Supplier<T> read) {
        try {
            return read.get();
        } catch (JacksonIOException failure) {
            throw new SynapseIOException(message, failure.getCause());
        } catch (JacksonException failure) {
            throw new SynapseException(message, failure);
        }
    }

}
