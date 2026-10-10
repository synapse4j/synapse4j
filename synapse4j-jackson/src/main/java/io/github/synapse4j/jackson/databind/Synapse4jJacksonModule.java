package io.github.synapse4j.jackson.databind;

import java.io.Reader;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSource;
import tools.jackson.databind.module.SimpleModule;

/**
 * A Jackson module that carries the values this library reads and writes through a {@code JsonMapper} as
 * the JSON each stands for rather than as the shape of its class, so a value that sits inside something
 * the mapper binds is read and written like any other member — anywhere in the object graph, a field of a
 * record included.
 *
 * <p>
 * Add it to the mapper before the codec is built: a mapper is immutable once built, so the module cannot
 * be added later. Its name is its registration id, so adding it twice leaves a single registration; and
 * where an application registered a serializer of its own for a value this module covers, this module's
 * registration wins, being appended after it.
 */
public class Synapse4jJacksonModule extends SimpleModule {

    /** The name Jackson reports for this module. */
    private static final String NAME = "Synapse4jJacksonModule";

    /** Creates the module with its serializers and deserializers registered. */
    public Synapse4jJacksonModule() {
        super(NAME);
        addSerializer(JsonSchema.class, new JsonSchemaSerializer());
        addDeserializer(JsonSchema.class, new JsonSchemaDeserializer());
        addSerializer(Reader.class, new ReaderSerializer());
        addSerializer(ProviderExtras.class, new ProviderExtrasSerializer());
        addSerializer(JsonSource.class, new JsonSourceSerializer());
    }

}
