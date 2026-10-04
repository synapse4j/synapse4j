package io.github.synapse4j.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.github.synapse4j.json.BooleanJsonSchema;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import io.github.synapse4j.json.JsonSchemas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class Synapse4jJacksonModuleTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new Synapse4jJacksonModule()).build();

    @Test
    void aSchemaIsWrittenAsTheDocumentItDescribes() {
        JsonSchema schema = objectSchema();

        // The schema's own document, not the shape of its class: without the module the mapper would
        // write its getters instead — type as an array, every other keyword as null.
        assertEquals(mapper.writeValueAsString(JsonSchemas.toDocument(schema)), mapper.writeValueAsString(schema));
    }

    @Test
    void aSchemaNestedInAValueIsWrittenAndReadBack() {
        Carries original = new Carries(objectSchema(), List.of(typed("string")));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"schema\":{\"type\":\"object\""), json);
        Carries back = mapper.readValue(json, Carries.class);
        assertEquals(JsonSchemas.toDocument(original.schema()), JsonSchemas.toDocument(back.schema()));
        assertEquals(JsonSchemas.toDocument(original.more().get(0)), JsonSchemas.toDocument(back.more().get(0)));
    }

    @Test
    void aBooleanSchemaSurvivesBothWays() {
        // Written: a boolean schema nested in an object schema goes out as its boolean.
        assertEquals("{\"not\":true}", mapper.writeValueAsString(Map.of("not", BooleanJsonSchema.TRUE)));

        // Read: a boolean at the root is a schema like any other.
        assertEquals(BooleanJsonSchema.FALSE, mapper.readValue("false", JsonSchema.class));
    }

    @Test
    void aValueInNoModelledFormIsRefused() {
        // "properties" is modelled as a map of schemas; a string there is a document this library
        // cannot read, and it is refused rather than carried along.
        MismatchedInputException thrown = assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"properties\":\"not a schema map\"}", JsonSchema.class));

        assertTrue(thrown.getMessage().contains("properties"), thrown.getMessage());
    }

    @Test
    void aKeywordTheLibraryHasNotModelledIsCarriedAsItIs() {
        // "type" is not modelled — the library types only the schema-valued keywords — so its value
        // is kept as the JSON data it is.
        JsonSchema schema = mapper.readValue("{\"type\":[\"object\",\"null\"]}", JsonSchema.class);

        assertEquals(List.of("object", "null"), schema.getType());
    }

    @Test
    void addingTheModuleTwiceRegistersItOnce() {
        JsonMapper twice = JsonMapper.builder()
                .addModule(new Synapse4jJacksonModule())
                .addModule(new Synapse4jJacksonModule())
                .build();

        // The module's name is its registration id, so the second add replaces the first rather than
        // registering a second: a mapper an application already taught about a JsonSchema is safe.
        assertEquals(1, twice.registeredModules().size());
    }

    /** A value carrying schemas the way a model type would: one field, and a list of them. */
    record Carries(JsonSchema schema, List<JsonSchema> more) {
    }

    private static JsonSchema objectSchema() {
        return new JsonSchemaBuilder().setType("object")
                .setProperties(Map.of("name", typed("string")))
                .build();
    }

    private static JsonSchema typed(String type) {
        return new JsonSchemaBuilder().setType(type).build();
    }

}
