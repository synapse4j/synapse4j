package io.github.synapse4j.json;

import java.io.Flushable;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * Writes one JSON document to a sink, token by token.
 *
 * <p>
 * This interface exists so that a document never has to exist in memory. A provider module
 * translates a request straight into the sink instead of building a structure and serializing it
 * afterwards, which for the documents this library sends — a conversation replayed in full, media
 * inlined as base64 — is the difference between holding the payload once and holding it three times.
 *
 * <p>
 * Only JDK types appear in these signatures. An implementation comes from the JSON library module
 * the application chose (Jackson, Gson, ...); applications and provider modules do not implement
 * this interface. An instance carries one document and is used by one thread — unlike the codec it
 * came from, it is not shareable.
 *
 * <p>
 * The sink belongs to the caller. {@link #flush()} pushes what is buffered into it and flushes it;
 * {@link #close()} does the same and then releases the writer's own buffers. Neither closes the
 * sink: the transport owns it, and may still have something to write or report after the document
 * is finished.
 *
 * <p>
 * A failure of the sink is a {@link SynapseIOException} whose cause is the original
 * {@link IOException}: one family to catch, with the transport detail still there for whoever needs
 * it. Whether the tokens written form a well-formed document is not something this interface
 * requires an implementation to check.
 */
public interface JsonWriter extends Flushable, AutoCloseable {

    /** Begins an object. Must be followed by property names, or by {@link #writeEndObject()}. */
    JsonWriter writeStartObject();

    /** Ends the object that is currently open. */
    JsonWriter writeEndObject();

    /** Begins an array. Must be followed by values, or by {@link #writeEndArray()}. */
    JsonWriter writeStartArray();

    /** Ends the array that is currently open. */
    JsonWriter writeEndArray();

    /**
     * Writes a property name.
     *
     * @param name the name; must not be {@code null}, and is written as given — a naming strategy
     *                 belongs to the caller, not here
     */
    JsonWriter writeName(String name);

    /**
     * Writes a string value.
     *
     * @param text the text, or {@code null} to write JSON {@code null}
     */
    JsonWriter writeString(String text);

    /**
     * Writes a string value whose characters come from a reader, so that a value too large to hold —
     * an inlined media payload, a long tool result — need not be held.
     *
     * <p>
     * The reader is read to its end and then closed — also when writing fails, since it typically
     * holds an open file or stream the caller has no other handle on. Holding less is the point, not
     * a promise: an implementation whose library cannot write a string incrementally may read it
     * into memory first, and the contract is the same either way.
     *
     * @param text the characters of the value; must not be {@code null}
     */
    JsonWriter writeString(Reader text);

    /**
     * Writes a number value.
     *
     * @param value the number
     */
    JsonWriter writeNumber(long value);

    /**
     * Writes a number value. The text must be locale-independent — a decimal separator taken from
     * the default locale would not be JSON.
     *
     * @param value the number
     */
    JsonWriter writeNumber(double value);

    /**
     * Writes a number value that the primitive {@code writeNumber} methods cannot hold exactly — a
     * {@link java.math.BigInteger}, or a decimal with more precision than a {@code double} keeps.
     * This is how a number {@link JsonReader#captureValue()} preserved is handed back without loss:
     * through a {@code double} it would be rounded, through a {@code long} it would overflow.
     *
     * @param value the number; must not be {@code null}
     */
    JsonWriter writeNumber(BigDecimal value);

    /**
     * Writes a number whose text is already the JSON spelling of it — the escape hatch for a number
     * this interface has no other method for. The text is written as given and must be a valid JSON
     * number: locale-independent, no surrounding whitespace.
     *
     * @param encoded the number's JSON text; must not be {@code null}
     */
    JsonWriter writeNumber(String encoded);

    /**
     * Writes a boolean value.
     *
     * @param value the value
     */
    JsonWriter writeBoolean(boolean value);

    /** Writes JSON {@code null}. */
    JsonWriter writeNull();

    /**
     * Writes a Java value into the document: the shapes a JSON document is made of, and the types
     * this library owns in the form they describe.
     *
     * <p>
     * A map becomes an object and a list an array, and a {@link Reader} as the string it yields, so a
     * payload larger than memory still goes out, and their members and elements are written the same
     * way, so a type this library owns is recognized wherever it sits. A {@link ProviderExtras} is
     * written as the object its paths describe, a {@link JsonSchema} as the document it describes. A
     * value none of those covers is handed to the JSON library this writer came from, which decides
     * what it becomes — a value whose fields the protocol spells differently belongs in a map instead.
     *
     * <p>
     * {@link AbstractJsonWriter} implements this over the tokens; an implementation that does not
     * extend it carries the same obligation.
     *
     * @param value the value to write; {@code null} writes JSON {@code null}
     */
    JsonWriter writeValue(Object value);

    /**
     * Pushes what is buffered into the sink and flushes it.
     *
     * @throws SynapseIOException if the sink fails
     */
    @Override
    void flush();

    /**
     * Equivalent to {@link #flush()} followed by releasing this writer's buffers. The sink is left
     * open, so this is not "close the stream": the transport decides when the sink is finished.
     *
     * @throws SynapseIOException if the sink fails
     */
    @Override
    void close();

}
