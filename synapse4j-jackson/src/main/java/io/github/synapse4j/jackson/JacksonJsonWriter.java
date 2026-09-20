package io.github.synapse4j.jackson;

import java.io.OutputStream;
import java.io.Reader;
import java.util.function.Supplier;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;
import io.github.synapse4j.json.JsonWriter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonFactory;

/**
 * A {@link JsonWriter} that writes through a Jackson {@link JsonGenerator}.
 *
 * <p>
 * Internal to this module: an application gets one from {@link JacksonJsonCodec#writer} and never
 * names this class. The generator comes from a factory that leaves the sink alone, so
 * {@link #close()} is Jackson's own "flush and release", not a stream close.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class JacksonJsonWriter implements JsonWriter {

    private final JsonGenerator generator;

    /**
     * Opens a writer over the given sink.
     *
     * <p>
     * Creating a generator can fail on the sink too, so that failure is translated here as well —
     * otherwise the caller would have to catch a Jackson exception from a method that promises this
     * library's own.
     */
    static JacksonJsonWriter open(JsonFactory factory, OutputStream out) {
        return translate("Opening the JSON writer failed",
                () -> new JacksonJsonWriter(factory.createGenerator(ObjectWriteContext.empty(), out)));
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
        // A negative length is Jackson's "read the reader to its end"; it does not close the reader.
        return write(() -> generator.writeString(text, -1));
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
    public JsonWriter writeBoolean(boolean value) {
        return write(() -> generator.writeBoolean(value));
    }

    @Override
    public JsonWriter writeNull() {
        return write(generator::writeNull);
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
