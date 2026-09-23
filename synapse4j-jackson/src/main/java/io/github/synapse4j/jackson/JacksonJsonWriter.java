package io.github.synapse4j.jackson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.function.Supplier;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;
import io.github.synapse4j.json.AbstractJsonWriter;
import io.github.synapse4j.json.JsonWriter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link JsonWriter} that writes through a Jackson {@link JsonGenerator}.
 *
 * <p>
 * Internal to this module: an application gets one from {@link JacksonJsonCodec#writer} and never
 * names this class. The generator comes from a factory that leaves the sink alone, so
 * {@link #close()} is Jackson's own "flush and release", not a stream close.
 *
 * <p>
 * The mapper is carried alongside for {@link #writeValueDirect(Object)}, the one value the tokens
 * cannot write.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class JacksonJsonWriter extends AbstractJsonWriter {

    private final JsonGenerator generator;

    /** Writes the values the token methods cannot; a generator on its own knows only tokens. */
    private final JsonMapper jsonMapper;

    /**
     * Opens a writer over the given sink.
     *
     * <p>
     * Creating a generator can fail on the sink too, so that failure is translated here as well —
     * otherwise the caller would have to catch a Jackson exception from a method that promises this
     * library's own.
     */
    static JacksonJsonWriter open(JsonFactory factory, JsonMapper mapper, OutputStream out) {
        return translate("Opening the JSON writer failed",
                () -> new JacksonJsonWriter(factory.createGenerator(ObjectWriteContext.empty(), out), mapper));
    }

    @Override
    public JsonWriter writeStartObject() {
        return write(generator::writeStartObject);
    }

    @Override
    public JsonWriter writeEndObject() {
        return write(generator::writeEndObject);
    }

    @Override
    public JsonWriter writeStartArray() {
        return write(generator::writeStartArray);
    }

    @Override
    public JsonWriter writeEndArray() {
        return write(generator::writeEndArray);
    }

    @Override
    public JsonWriter writeName(String name) {
        return write(() -> generator.writeName(name));
    }

    @Override
    public JsonWriter writeString(String text) {
        return write(() -> generator.writeString(text));
    }

    @Override
    public JsonWriter writeString(Reader text) {
        try {
            // A negative length is Jackson's "read the reader to its end"; it does not close the
            // reader, so this method does — also when the write fails, see the interface contract.
            return write(() -> generator.writeString(text, -1));
        } finally {
            try {
                text.close();
            } catch (IOException failure) {
                throw new SynapseIOException("Closing the string source failed", failure);
            }
        }
    }

    @Override
    public JsonWriter writeNumber(long value) {
        return write(() -> generator.writeNumber(value));
    }

    @Override
    public JsonWriter writeNumber(double value) {
        return write(() -> generator.writeNumber(value));
    }

    @Override
    public JsonWriter writeNumber(BigDecimal value) {
        return write(() -> generator.writeNumber(value));
    }

    @Override
    public JsonWriter writeNumber(String encoded) {
        return write(() -> generator.writeNumber(encoded));
    }

    @Override
    public JsonWriter writeBoolean(boolean value) {
        return write(() -> generator.writeBoolean(value));
    }

    @Override
    public JsonWriter writeNull() {
        return write(generator::writeNull);
    }

    @Override
    protected JsonWriter writeValueDirect(Object value) {
        // The mapper writes into the open generator, so the value reaches the sink as it is
        // serialized rather than being turned into text first.
        return write(() -> jsonMapper.writeValue(generator, value));
    }

    @Override
    public void flush() {
        write(generator::flush);
    }

    @Override
    public void close() {
        write(generator::close);
    }

    /** Runs one write and returns this writer, so that the interface stays fluent. */
    private JsonWriter write(Runnable write) {
        return translate("Writing the JSON document failed", () -> {
            write.run();
            return this;
        });
    }

    /** Runs one write, translating what Jackson reports into what this library reports. */
    private static <T> T translate(String message, Supplier<T> write) {
        try {
            return write.get();
        } catch (JacksonIOException failure) {
            throw new SynapseIOException(message, failure.getCause());
        } catch (JacksonException failure) {
            throw new SynapseException(message, failure);
        }
    }

}
