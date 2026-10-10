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
 * stream the caller has no other handle on.
 */
class ReaderSerializer extends ValueSerializer<Reader> {

    @Override
    public void serialize(Reader value, JsonGenerator gen, SerializationContext ctxt) {
        try {
            // A negative length is Jackson's "read the reader to its end"; it does not close the
            // reader, so this method does.
            gen.writeString(value, -1);
        } finally {
            try {
                value.close();
            } catch (IOException failure) {
                throw JacksonIOException.construct(failure);
            }
        }
    }

}
