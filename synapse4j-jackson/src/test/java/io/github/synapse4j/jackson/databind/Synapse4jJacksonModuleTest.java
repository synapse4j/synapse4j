package io.github.synapse4j.jackson.databind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.json.BooleanJsonSchema;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import io.github.synapse4j.json.JsonSource;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class Synapse4jJacksonModuleTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new Synapse4jJacksonModule()).build();

    @Test
    void schemaWrittenAsDocument() {
        // The schema's own document, not the shape of its class: without the module the mapper would
        // write its getters instead — type as an array, every other keyword as null.
        Map<?, ?> written = mapper.readValue(mapper.writeValueAsString(objectSchema()), Map.class);
        Map<?, ?> document = mapper.readValue(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}", Map.class);

        assertEquals(document, written);
    }

    @Test
    void nestedSchemaRoundTrips() {
        Carries original = new Carries(objectSchema(), List.of(typed("string")));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"schema\":{\"type\":\"object\""), json);
        Carries back = mapper.readValue(json, Carries.class);
        assertEquals(original.schema(), back.schema());
        assertEquals(original.more().get(0), back.more().get(0));
    }

    @Test
    void booleanSchemaSurvivesBothWays() {
        // Written: a boolean schema nested in an object schema goes out as its boolean.
        assertEquals("{\"not\":true}", mapper.writeValueAsString(Map.of("not", BooleanJsonSchema.TRUE)));

        // Read: a boolean at the root is a schema like any other.
        assertEquals(BooleanJsonSchema.FALSE, mapper.readValue("false", JsonSchema.class));
    }

    @Test
    void unmodelledValueRefused() {
        // "properties" is modelled as a map of schemas; a string there is a document this library
        // cannot read, and it is refused rather than carried along.
        MismatchedInputException thrown = assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"properties\":\"not a schema map\"}", JsonSchema.class));

        assertTrue(thrown.getMessage().contains("properties"), thrown.getMessage());
    }

    @Test
    void unmodelledKeywordCarriedAsIs() {
        // "type" is not modelled — the library types only the schema-valued keywords — so its value
        // is kept as the JSON data it is.
        JsonSchema schema = mapper.readValue("{\"type\":[\"object\",\"null\"]}", JsonSchema.class);

        assertEquals(List.of("object", "null"), schema.getType());
    }

    @Test
    void moduleAddedTwiceSchemaReadable() {
        JsonMapper twice = JsonMapper.builder()
                .addModule(new Synapse4jJacksonModule())
                .addModule(new Synapse4jJacksonModule())
                .build();

        // The module's name is its registration id, so the second add replaces the first rather than
        // registering a second: a mapper an application already taught about a JsonSchema still reads
        // and writes one as the document it describes.
        assertEquals(1, twice.registeredModules().stream().filter(Synapse4jJacksonModule.class::isInstance)
                .count());
        JsonSchema schema = objectSchema();
        assertEquals(schema, twice.readValue(twice.writeValueAsString(schema), JsonSchema.class));
    }

    @Test
    void libraryTypesWrittenAsDocuments() {
        ProviderExtras extras = new ProviderExtras().put("temperature", 0);

        String json = mapper.writeValueAsString(new Payload(new StringReader("hello"), extras, () -> "deferred"));

        assertEquals(Map.of("text", "hello", "extras", Map.of("temperature", 0), "source", "deferred"),
                mapper.readValue(json, Map.class));
    }

    @Test
    void readerClosedAfterWriting() {
        AtomicBoolean closed = new AtomicBoolean();
        Reader text = new StringReader("hello") {

            @Override
            public void close() {
                closed.set(true);
                super.close();
            }

        };

        assertEquals("\"hello\"", mapper.writeValueAsString(text));
        assertTrue(closed.get());
    }

    /** A value carrying schemas the way a model type would: one field, and a list of them. */
    record Carries(JsonSchema schema, List<JsonSchema> more) {
    }

    /** A value carrying the other types the module binds, the way a model type would. */
    record Payload(Reader text, ProviderExtras extras, JsonSource source) {
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
