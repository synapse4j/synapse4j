package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ObjectJsonSchemaTest {

    @Test
    void newSchemaCarriesNothing() {
        JsonSchema schema = built();

        assertNull(schema.asBoolean());
        assertNull(schema.getType());
        assertNull(schema.getTitle());
        assertNull(schema.getDescription());
        assertNull(schema.getProperties());
        assertNull(schema.getRequired());
        assertNull(schema.getItems());
        assertNull(schema.getAdditionalProperties());
        assertNull(schema.getRef());
        assertNull(schema.getDefs());
        assertTrue(schema.keys().isEmpty());
        assertNull(schema.get("format", Object.class));
        assertTrue(document(schema).isEmpty());
    }

    @Test
    void anEmptyKeywordIsNotTheSameAsAbsent() {
        JsonSchema absent = built();
        JsonSchema empty = JsonSchemas.fromDocument(Map.of(
                "required", List.of(),
                "properties", Map.of()));

        assertNull(absent.getRequired());
        assertNull(absent.getProperties());
        assertEquals(List.of(), empty.getRequired());
        assertEquals(Map.of(), empty.getProperties());
        assertEquals(Map.of("required", List.of(), "properties", Map.of()), JsonSchemas.toDocument(empty));
    }

    @Test
    void readingDoesNotChangeTheSchema() {
        JsonSchema schema = built();

        schema.getType();
        schema.getProperties();
        schema.getRequired();
        schema.getDefs();
        schema.getItems();

        assertTrue(schema.keys().isEmpty());
        assertTrue(document(schema).isEmpty());
    }

    @Test
    void theBuiltSchemaIsFrozen() {
        // Nothing a getter hands out can change the schema: the top map and every collection below it
        // are unmodifiable.
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setRequired(List.of("name"))
                .setProperties(Map.of("name", typed("string")))
                .put("enum", new ArrayList<>(List.of("a")))
                .build();

        assertThrows(UnsupportedOperationException.class, () -> schema.keys().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.getRequired().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> schema.getProperties().put("other", typed("string")));
        assertThrows(UnsupportedOperationException.class, () -> rawList(schema, "enum").add("b"));
    }

    @Test
    void aSingleTypeIsWrittenAsAStringAndSeveralAsAnArray() {
        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(typed("object")));

        JsonSchema nullable = new JsonSchemaBuilder().setType(List.of("string", "null")).build();

        assertEquals(Map.of("type", List.of("string", "null")), JsonSchemas.toDocument(nullable));
    }

    @Test
    void theTypeFormFollowsTheSetterThatWasUsed() {
        // Set as a string, written as a string; set as a list, written as an array — a one-element
        // list is not folded into the string form.
        JsonSchema single = new JsonSchemaBuilder().setType("object").build();
        JsonSchema array = new JsonSchemaBuilder().setType(List.of("object")).build();

        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(single));
        assertEquals(Map.of("type", List.of("object")), JsonSchemas.toDocument(array));
        assertEquals(List.of("object"), single.getType());
        assertEquals(List.of("object"), array.getType());
    }

    @Test
    void onlyWhatIsSetIsWritten() {
        JsonSchema schema = typed("object");

        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(schema));
    }

    @Test
    void nestedSchemasAreWrittenAsMaps() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setProperties(Map.of("name", typed("string")))
                .setItems(typed("number"))
                .setDefs(Map.of("D", typed("boolean")))
                .setAdditionalProperties(BooleanJsonSchema.FALSE)
                .build();

        Map<String, Object> map = document(schema);

        assertEquals(Map.of("name", Map.of("type", "string")), map.get("properties"));
        assertEquals(Map.of("type", "number"), map.get("items"));
        assertEquals(Map.of("D", Map.of("type", "boolean")), map.get("$defs"));
        assertEquals(false, map.get("additionalProperties"));
    }

    @Test
    void extraKeywordsAreReadThroughKeysAndGet() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("format", "uuid")
                .build();

        assertEquals(Set.of("type", "$schema", "format"), schema.keys());
        assertEquals("uuid", schema.get("format", String.class));
        assertEquals("uuid", schema.get("format"));

        Map<String, Object> map = document(schema);
        assertEquals("https://json-schema.org/draft/2020-12/schema", map.get("$schema"));
        assertEquals("uuid", map.get("format"));
        assertEquals("object", map.get("type"));
    }

    @Test
    void titleAndDescriptionAreNamedKeywords() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setTitle("WeatherQuery")
                .setDescription("A request for the weather.")
                .build();

        assertEquals("WeatherQuery", schema.getTitle());
        assertEquals("A request for the weather.", schema.getDescription());
        assertEquals(Set.of("type", "title", "description"), schema.keys());
        assertEquals("WeatherQuery", schema.get("title", String.class));
        assertEquals(Map.of("type", "object", "title", "WeatherQuery",
                "description", "A request for the weather."), JsonSchemas.toDocument(schema));
    }

    @Test
    void everyModelledKeywordSurvivesARoundTrip() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType(List.of("string", "null"))
                .setTitle("WeatherQuery")
                .setDescription("A request for the weather.")
                .setAdditionalProperties(BooleanJsonSchema.FALSE)
                .setRequired(List.of("locations"))
                .setProperties(Map.of("locations", typed("array")))
                .setItems(typed("string"))
                .setDefs(Map.of("Location", typed("object")))
                .setRef("#/$defs/Location")
                .put("format", "uuid")
                .build();

        JsonSchema read = JsonSchemas.fromDocument(JsonSchemas.toDocument(schema));

        assertEquals(JsonSchemas.toDocument(schema), JsonSchemas.toDocument(read));
    }

    @Test
    void anExplicitNullIsCarried() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("const", null);

        JsonSchema schema = JsonSchemas.fromDocument(document);

        assertEquals(Set.of("const"), schema.keys());
        assertNull(schema.get("const", Object.class));
        assertEquals(document, JsonSchemas.toDocument(schema));
    }

    @Test
    void fromDocumentCarriesKeywordsItDoesNotModel() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "const", 5,
                "$schema", "https://json-schema.org/draft/2020-12/schema"));

        assertEquals(List.of("object"), schema.getType());
        assertEquals(Integer.valueOf(5), schema.get("const", Integer.class));
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema", String.class));
        assertEquals(Map.of("type", "object", "const", 5,
                "$schema", "https://json-schema.org/draft/2020-12/schema"), JsonSchemas.toDocument(schema));
    }

    @Test
    void everySchemaValuedKeywordIsWalked() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "allOf", List.of(Map.of("type", "string")),
                "not", Map.of("type", "null"),
                "propertyNames", Map.of("pattern", "^[a-z]+$"),
                "properties", Map.of("name", Map.of("type", "string")),
                "prefixItems", List.of(Map.of("type", "number"))));

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);

        assertEquals(6, visited.size());
        assertEquals(JsonSchemas.toDocument(schema),
                JsonSchemas.toDocument(JsonSchemas.fromDocument(JsonSchemas.toDocument(schema))));
    }

    @Test
    void booleanSchemasAreReadAsSchemas() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "additionalProperties", false,
                "not", true));

        assertEquals(BooleanJsonSchema.FALSE, schema.getAdditionalProperties());
        assertEquals(BooleanJsonSchema.TRUE, schema.get("not", JsonSchema.class));
        assertEquals(Map.of("additionalProperties", false, "not", true), JsonSchemas.toDocument(schema));
    }

    @Test
    void aBooleanSchemaIsItsOwnDocument() {
        assertSame(BooleanJsonSchema.TRUE, JsonSchemas.fromDocument(true));
        assertSame(BooleanJsonSchema.FALSE, JsonSchemas.fromDocument(false));
        assertEquals(Boolean.TRUE, JsonSchemas.toDocument(BooleanJsonSchema.TRUE));
        assertEquals(Boolean.FALSE, JsonSchemas.toDocument(BooleanJsonSchema.FALSE));
    }

    @Test
    void nestedBooleanSchemasSurviveARoundTrip() {
        // The dispatch is one function: a schema nested in a map is read the way the root is, so a
        // boolean nested in a map is a schema like any other.
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "properties", Map.of("name", Map.of("type", "string")),
                "additionalProperties", false,
                "items", true));

        assertEquals(BooleanJsonSchema.FALSE, schema.getAdditionalProperties());
        assertSame(BooleanJsonSchema.TRUE, schema.getItems());
        assertEquals(Map.of(
                "properties", Map.of("name", Map.of("type", "string")),
                "additionalProperties", false,
                "items", true), JsonSchemas.toDocument(schema));
    }

    @Test
    void anItemsListIsWalked() {
        // 2020-12 makes "items" a single schema; draft-07 allowed an array. The array is read and
        // walked as a list of schemas, even though the interface only models the single one.
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "items", List.of(Map.of("type", "string"), Map.of("type", "number"))));

        assertNull(schema.getItems());

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);

        assertEquals(3, visited.size());
        assertEquals(Map.of("items", List.of(Map.of("type", "string"), Map.of("type", "number"))),
                JsonSchemas.toDocument(schema));
    }

    @Test
    void visitReachesEverySchema() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setProperties(Map.of("name", typed("string")))
                .setItems(typed("object"))
                .build();

        List<JsonSchema> seen = new ArrayList<>();
        schema.visit(seen::add);

        assertEquals(3, seen.size());
        assertEquals(schema, seen.get(0));
    }

    @Test
    void mapRebuildsOnlyThePathThatChanged() {
        JsonSchema name = typed("string");
        JsonSchema schema = new JsonSchemaBuilder().setProperties(Map.of("name", name)).build();

        JsonSchema mapped = schema.map(node -> node == name ? typed("number") : node);

        assertNotSame(schema, mapped);
        assertTrue(mapped instanceof ObjectJsonSchema);
        assertEquals(Map.of("properties", Map.of("name", Map.of("type", "number"))),
                JsonSchemas.toDocument(mapped));
        assertEquals(List.of("string"), name.getType());
    }

    @Test
    void mapSharesNodesItLeavesAlone() {
        JsonSchema schema = new JsonSchemaBuilder().setProperties(Map.of("name", typed("string"))).build();

        assertSame(schema, schema.map(node -> node));
    }

    @Test
    void toStringRendersTheDocumentShape() {
        assertEquals("ObjectJsonSchema{type=object}", typed("object").toString());
    }

    @Test
    void aSubSchemaReachedTwiceIsWrittenAndVisitedAtEachPath() {
        // JSON has no way to share one, so a node reached through two paths is written out at both and
        // walked at both.
        JsonSchema shared = typed("string");
        JsonSchema schema = new JsonSchemaBuilder().setProperties(Map.of("first", shared, "second", shared)).build();

        assertEquals(Map.of("properties",
                Map.of("first", Map.of("type", "string"), "second", Map.of("type", "string"))),
                JsonSchemas.toDocument(schema));

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);
        assertEquals(List.of(schema, shared, shared), visited);
    }

    private static JsonSchema built() {
        return new JsonSchemaBuilder().build();
    }

    private static JsonSchema typed(String type) {
        return new JsonSchemaBuilder().setType(type).build();
    }

    /** The document of an object-form schema, as the map a test compares against. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(JsonSchema schema) {
        return (Map<String, Object>) JsonSchemas.toDocument(schema);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rawList(JsonSchema schema, String keyword) {
        return (List<Object>) schema.get(keyword);
    }

}
