package io.github.synapse4j.jackson;

import io.github.synapse4j.json.JsonSchema;
import tools.jackson.databind.module.SimpleModule;

/**
 * Teaches a Jackson {@code JsonMapper} to read and write this library's own types as the JSON
 * documents they describe — anywhere in the object graph, a field of a record included.
 *
 * <p>
 * This is a Jackson module, not one of the victools modules beside it: it registers a serializer and a
 * deserializer for each type whose document form is not the shape of its class — {@link JsonSchema}
 * today, the rest as they are modelled — so such a value that sits inside something the mapper binds
 * is written and read like any other member. Add it to the mapper before the codec is built; a mapper
 * is immutable once built, so the module cannot be added later.
 *
 * <p>
 * A type is registered under the interface it is reached by, so a {@link JsonSchema} is covered in both
 * its object and boolean forms, and by any implementation of it.
 *
 * <p>
 * The name it is built with doubles as its registration id, so adding it to a mapper that already
 * carries one replaces that instance rather than registering a second — adding it is idempotent, and a
 * mapper an application has already taught about {@link JsonSchema} is safe to hand in. The name is
 * therefore load-bearing: an unnamed {@code SimpleModule} would be given a fresh id per instance and
 * would register twice.
 */
public class Synapse4jJacksonModule extends SimpleModule {

    /** The name Jackson reports for this module. */
    private static final String NAME = "Synapse4jJacksonModule";

    /** Creates the module with its serializers and deserializers registered. */
    public Synapse4jJacksonModule() {
        super(NAME);
        addSerializer(JsonSchema.class, new JsonSchemaSerializer());
        addDeserializer(JsonSchema.class, new JsonSchemaDeserializer());
    }

}
