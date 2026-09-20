package io.github.synapse4j.json;

import java.io.IOException;
import java.io.Writer;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * Reads one JSON document from a source, token by token.
 *
 * <p>
 * This interface exists so that reading does not have to go through a tree. A provider module can
 * walk a response and build the shared model in a single pass, keep or skip what it does not model,
 * and stream out a value too large to hold — the response side of the same trade {@link JsonWriter}
 * makes on the way out.
 *
 * <p>
 * Only JDK types appear in these signatures. An implementation comes from the JSON library module
 * the application chose; applications and provider modules do not implement this interface. An
 * instance carries one document and is used by one thread — unlike the codec it came from, it is
 * not shareable.
 *
 * <p>
 * The source belongs to the caller: {@link #close()} releases the reader's own buffers and never
 * closes the source.
 *
 * <p>
 * A failure of the source is a {@link SynapseIOException} whose cause is the original
 * {@link IOException}. A document that is not valid JSON is a {@link SynapseException}, and asking a
 * token for a value it cannot have is an {@link IllegalStateException}: the peer's fault and the
 * caller's respectively, neither of them a broken stream.
 */
public interface JsonReader extends AutoCloseable {

    /**
     * What the reader is positioned on.
     *
     * <p>
     * The kinds are fixed by the JSON grammar and nobody extends them, which is why a closed form is
     * right here while the rest of this library's structures avoid one: the rule against {@code enum}
     * exists so that callers and providers can extend the structures they pass around, and a token
     * kind is not one of those. The reader being positioned on nothing at all is not a kind of token
     * and is not in here: {@link #token()} answers {@code null} for it.
     */
    enum Token {

        /** The start of an object. Its properties follow as {@link #NAME} and value token pairs. */
        START_OBJECT,

        /** The end of the object currently open. */
        END_OBJECT,

        /** The start of an array. Its elements follow as value tokens. */
        START_ARRAY,

        /** The end of the array currently open. */
        END_ARRAY,

        /** A property name, available through {@link JsonReader#name()}. */
        NAME,

        /** A string value. */
        STRING,

        /** A number value, whatever its textual form. */
        NUMBER,

        /** The value {@code true}. The value is the token; there is nothing more to read. */
        TRUE,

        /** The value {@code false}. The value is the token; there is nothing more to read. */
        FALSE,

        /** The value {@code null}. The value is the token; there is nothing more to read. */
        NULL,

        /** The end of the document, returned once and on every later call. */
        END_DOCUMENT,

    }

    /**
     * Advances to the next token and returns it.
     *
     * <p>
     * The first call returns the document's first token. Tokens arrive in document order: inside an
     * object, a {@link Token#NAME} is followed by the value it names, and nesting is visible as the
     * {@code START_*} and {@code END_*} pairs around it.
     *
     * @return the token now current, or {@link Token#END_DOCUMENT}
     */
    Token nextToken();

    /**
     * Returns the token the reader is on — the one the last {@link #nextToken()} answered with.
     *
     * <p>
     * A caller that reads a value in one call does not need this: it already holds the token that
     * call returned. One that reads a value in several calls does, and {@link #captureValue()} is
     * the operation in this interface that does.
     *
     * @return the current token, or {@code null} before the first {@link #nextToken()} call
     */
    Token token();

    /**
     * Returns the name of the property whose value comes next.
     *
     * @return the name, or {@code null} when the current token is not {@link Token#NAME}
     */
    String name();

    /**
     * Returns the text of the current token, when it has one: a string's characters, or a number's
     * textual form for the caller to parse.
     *
     * <p>
     * Callers take booleans and nulls from the token itself ({@link Token#TRUE}, {@link Token#FALSE},
     * {@link Token#NULL}), and numbers through {@link #longValue()} or {@link #doubleValue()} when
     * they want a number rather than its text.
     *
     * @return the text, or {@code null} when the current token carries none
     */
    String string();

    /**
     * Returns the current number as a {@code long}.
     *
     * @return the value
     * @throws IllegalStateException if the current token is not a number
     * @throws SynapseException      if the number is not an integer or does not fit — a wrong
     *                                   answer is never returned
     */
    long longValue();

    /**
     * Returns the current number as a {@code double}.
     *
     * @return the value
     * @throws IllegalStateException if the current token is not a number
     */
    double doubleValue();

    /**
     * Returns the value of the current boolean token.
     *
     * @return {@code true} for {@link Token#TRUE}, {@code false} for {@link Token#FALSE}
     * @throws IllegalStateException if the current token is neither
     */
    boolean booleanValue();

    /**
     * Skips the value the reader is positioned on: an object or an array is consumed through its
     * matching end token, a scalar is already consumed. This is how a field nobody models is passed
     * over without building anything for it.
     */
    void skipValue();

    /**
     * Reads the value the reader is positioned on — a scalar, or an object or array together with
     * everything under it — and returns it in the shape a decoded document has: a {@code Map} with
     * its keys in document order, a {@code List}, a {@code String}, a whole number as the narrowest
     * of {@code Integer}, {@code Long} and {@code BigInteger} that holds it, anything else numeric as
     * a {@code Double}, a {@code Boolean}, or {@code null}.
     *
     * <p>
     * This is how a caller keeps a value it does not model, where {@link #skipValue()} throws it
     * away — the two are the whole choice a provider module makes about the fields it does not read
     * into its own model. It is also the one operation here that costs memory proportional to what it
     * reads, which is the price of keeping it.
     *
     * <p>
     * The value is consumed, exactly as with {@link #skipValue()}: an object or array is read through
     * its matching end token.
     *
     * <p>
     * Reading it over the primitives above is what {@link AbstractJsonReader} does, so an
     * implementation that extends that class does not write it.
     *
     * @return the value; {@code null} for JSON {@code null}
     * @throws IllegalStateException if the reader is not positioned on a value
     */
    Object captureValue();

    /**
     * Returns the current string token's characters, writing them to the given writer instead of
     * returning them.
     *
     * <p>
     * This is the same operation as {@link #string()} with a different destination: where
     * {@link #string()} has to hold the whole value to hand it back, this hands it over as it is
     * read. Holding less is the point, not a promise — an implementation whose library cannot write a
     * string incrementally may assemble it first, and the contract is the same either way.
     *
     * <p>
     * The content is consumed: once this returns, {@link #string()} no longer has it. The writer is
     * not closed.
     *
     * @param out where the characters go
     * @return the number of characters written
     * @throws IllegalStateException if the current token is not a string
     * @throws SynapseIOException    if the source or the given writer fails
     */
    long string(Writer out);

    /**
     * {@inheritDoc}
     *
     * <p>
     * Releases this reader's buffers. The source is left open.
     */
    @Override
    void close();

}
