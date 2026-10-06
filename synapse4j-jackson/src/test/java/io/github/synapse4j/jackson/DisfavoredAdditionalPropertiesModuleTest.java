package io.github.synapse4j.jackson;

import static io.github.synapse4j.jackson.SchemaFixtures.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;

import org.junit.jupiter.api.Test;

import com.github.victools.jsonschema.generator.SchemaGenerator;

import tools.jackson.databind.JsonNode;

/**
 * Pins what {@link DisfavoredAdditionalPropertiesModule} decides about one type's node: closed where
 * there is an object that lists properties to close, and untouched where there is not.
 */
class DisfavoredAdditionalPropertiesModuleTest {

    /** A type that lists a property, so there is an object to close. */
    record HasProperties(String value) {
    }

    /** A type whose properties the generator cannot enumerate, so there is nothing to close. */
    interface NoProperties {
    }

    @Test
    void anObjectThatListsPropertiesIsClosed() {
        JsonNode schema = generate(HasProperties.class);

        assertEquals(false, schema.get("additionalProperties").asBoolean(), schema.toString());
    }

    @Test
    void anObjectThatListsNoPropertyIsNotClosed() {
        // A type the generator cannot enumerate — an interface — is described as an object with no
        // property, and closing it would say it admits nothing at all.
        assertFalse(generate(NoProperties.class).has("additionalProperties"));
    }

    @Test
    void aMapKeepsTheValueSchemaItAlreadyCarries() {
        JsonNode schema = new SchemaGenerator(JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(MAPPER, null)
                .with(new DisfavoredAdditionalPropertiesModule())
                .with(SchemaFixtures.mapValues())
                .build()).generateSchema(SchemaFixtures.MAP_OF_STRING);

        assertTrue(schema.get("additionalProperties").isObject(), schema.toString());
    }

    private static JsonNode generate(Type type) {
        return new SchemaGenerator(JacksonSchemaConfigBuilders.decodeSchemaConfigBuilder(MAPPER, null)
                .with(new DisfavoredAdditionalPropertiesModule())
                .build()).generateSchema(type);
    }

}
