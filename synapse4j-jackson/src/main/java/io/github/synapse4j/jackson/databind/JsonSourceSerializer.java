package io.github.synapse4j.jackson.databind;

import io.github.synapse4j.json.JsonSource;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link JsonSource} as the value it stands for, wherever the mapper meets one.
 *
 * <p>
 * The value is handed back to the mapper rather than written here, so a source that yields another
 * source defers one more level instead of being written as itself.
 */
class JsonSourceSerializer extends ValueSerializer<JsonSource> {

    @Override
    public void serialize(JsonSource value, JsonGenerator gen, SerializationContext ctxt) {
        ctxt.writeValue(gen, value.toJson());
    }

}
