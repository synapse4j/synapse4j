package io.github.synapse4j.jackson.databind;

import io.github.synapse4j.data.ProviderExtras;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link ProviderExtras} as the object its paths describe, wherever the mapper meets one.
 *
 * <p>
 * The nested map is handed back to the mapper rather than written here, so a value inside it is written
 * by whatever writes that value — this library's own types among them.
 */
class ProviderExtrasSerializer extends ValueSerializer<ProviderExtras> {

    @Override
    public void serialize(ProviderExtras value, JsonGenerator gen, SerializationContext ctxt) {
        ctxt.writeValue(gen, value.nestedMap());
    }

}
