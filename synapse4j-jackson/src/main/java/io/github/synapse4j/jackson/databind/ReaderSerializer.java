package io.github.synapse4j.jackson.databind;

import java.io.IOException;
import java.io.Reader;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link Reader} as the JSON string its characters make, wherever the mapper meets one.
 *
 * <p>
 * The characters are read incrementally, so a value larger than memory still goes out, and the reader is
 * closed once they are written — also when writing fails, since it typically holds an open file or
 * stream the caller has no other handle on. A close that fails is carried with that failure rather than
 * replacing it, so the reason the write failed still reaches the caller.
 */
class ReaderSerializer extends ValueSerializer<Reader> {

    @Override
    public void serialize(Reader value, JsonGenerator gen, SerializationContext ctxt) {
        Throwable writeFailure = null;
        try {
            // A negative length is Jackson's "read the reader to its end"; it does not close the
            // reader, so this method does.
            gen.writeString(value, -1);
        } catch (RuntimeException | Error failure) {
            writeFailure = failure;
            throw failure;
        } finally {
            try {
                value.close();
            } catch (IOException closeFailure) {
                if (writeFailure == null) {
                    throw JacksonIOException.construct(closeFailure);
                }
                // A failing close must not replace the failure that came first: it rides with it.
                writeFailure.addSuppressed(closeFailure);
            }
        }
    }

}
