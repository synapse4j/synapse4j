package io.github.synapse4j.json;

import java.io.Flushable;
import java.io.IOException;
import java.io.Reader;

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
 * requires an implementation to check; one that does check reports a violation as an
 * {@link IllegalStateException}, which is the caller's bug rather than a broken transport.
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
     * The reader is read to its end and is not closed. Holding less is the point, not a promise: an
     * implementation whose library cannot write a string incrementally may read it into memory first,
     * and the contract is the same either way.
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
     * Writes a boolean value.
     *
     * @param value the value
     */
    JsonWriter writeBoolean(boolean value);

    /** Writes JSON {@code null}. */
    JsonWriter writeNull();

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
