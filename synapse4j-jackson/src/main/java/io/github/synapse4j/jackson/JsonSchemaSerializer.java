package io.github.synapse4j.jackson;

import io.github.synapse4j.json.JsonSchema;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link JsonSchema} as the JSON document it describes, wherever the mapper meets one.
 *
 * <p>
 * A boolean schema goes out as its boolean; an object schema as an object whose every keyword is
 * written in turn, the keyword's value handed back to the mapper so a sub-schema, or a list or map of
 * them, reaches this serializer again. So the JSON is the schema's own document, never the shape of
 * its class.
 */
class JsonSchemaSerializer extends ValueSerializer<JsonSchema> {

    @Override
    public void serialize(JsonSchema value, JsonGenerator gen, SerializationContext ctxt) {
        Boolean asBoolean = value.asBoolean();
        if (asBoolean != null) {
            gen.writeBoolean(asBoolean);
            return;
        }
        gen.writeStartObject();
        for (String keyword : value.keys()) {
            gen.writeName(keyword);
            ctxt.writeValue(gen, value.get(keyword));
        }
        gen.writeEndObject();
    }

}
