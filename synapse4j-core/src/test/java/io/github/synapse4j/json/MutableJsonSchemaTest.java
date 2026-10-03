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

import io.github.synapse4j.exception.SynapseException;
import org.junit.jupiter.api.Test;

class MutableJsonSchemaTest {

    @Test
    void newSchemaCarriesNothing() {
        MutableJsonSchema schema = new MutableJsonSchema();

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
        JsonSchema absent = new MutableJsonSchema();
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
        MutableJsonSchema schema = new MutableJsonSchema();

        schema.getType();
        schema.getProperties();
        schema.getRequired();
        schema.getDefs();
        schema.getItems();

        assertTrue(schema.keys().isEmpty());
        assertTrue(document(schema).isEmpty());
    }

    @Test
    void aSingleTypeIsWrittenAsAStringAndSeveralAsAnArray() {
        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(typed("object")));

        MutableJsonSchema nullable = new MutableJsonSchema();
        nullable.setType(List.of("string", "null"));

        assertEquals(Map.of("type", List.of("string", "null")), JsonSchemas.toDocument(nullable));
    }

    @Test
    void theTypeFormFollowsTheSetterThatWasUsed() {
        // Set as a string, written as a string; set as a list, written as an array — a one-element
        // list is not folded into the string form.
        MutableJsonSchema single = new MutableJsonSchema();
        single.setType("object");
        MutableJsonSchema array = new MutableJsonSchema();
        array.setType(List.of("object"));

        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(single));
        assertEquals(Map.of("type", List.of("object")), JsonSchemas.toDocument(array));
        assertEquals(List.of("object"), single.getType());
        assertEquals(List.of("object"), array.getType());
    }

    @Test
    void onlyWhatIsSetIsWritten() {
        MutableJsonSchema schema = typed("object");

        assertEquals(Map.of("type", "object"), JsonSchemas.toDocument(schema));
    }

    @Test
    void nestedSchemasAreWrittenAsMaps() {
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setProperties(Map.of("name", typed("string")));
        schema.setItems(typed("number"));
        schema.setDefs(Map.of("D", typed("boolean")));
        schema.setAdditionalProperties(BooleanJsonSchema.FALSE);

        Map<String, Object> map = document(schema);

        assertEquals(Map.of("name", Map.of("type", "string")), map.get("properties"));
        assertEquals(Map.of("type", "number"), map.get("items"));
        assertEquals(Map.of("D", Map.of("type", "boolean")), map.get("$defs"));
        assertEquals(false, map.get("additionalProperties"));
    }

    @Test
    void extraKeywordsAreReadThroughKeysAndGet() {
        MutableJsonSchema schema = typed("object");
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("format", "uuid");

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
        MutableJsonSchema schema = typed("object");
        schema.setTitle("WeatherQuery");
        schema.setDescription("A request for the weather.");

        assertEquals("WeatherQuery", schema.getTitle());
        assertEquals("A request for the weather.", schema.getDescription());
        assertEquals(Set.of("type", "title", "description"), schema.keys());
        assertEquals("WeatherQuery", schema.get("title", String.class));
        assertEquals(Map.of("type", "object", "title", "WeatherQuery",
                "description", "A request for the weather."), JsonSchemas.toDocument(schema));
    }

    @Test
    void everyModelledKeywordSurvivesARoundTrip() {
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setType(List.of("string", "null"));
        schema.setTitle("WeatherQuery");
        schema.setDescription("A request for the weather.");
        schema.setAdditionalProperties(BooleanJsonSchema.FALSE);
        schema.setRequired(List.of("locations"));
        schema.setProperties(Map.of("locations", typed("array")));
        schema.setItems(typed("string"));
        schema.setDefs(Map.of("Location", typed("object")));
        schema.setRef("#/$defs/Location");
        schema.put("format", "uuid");

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
    void settingAModelledKeywordToNullClearsIt() {
        MutableJsonSchema schema = typed("object");
        schema.setTitle("WeatherQuery");
        schema.setTitle(null);

        assertEquals(Set.of("type"), schema.keys());
        assertNull(schema.getTitle());
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
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setProperties(Map.of("name", typed("string")));
        schema.setItems(typed("object"));

        List<JsonSchema> seen = new ArrayList<>();
        schema.visit(seen::add);

        assertEquals(3, seen.size());
        assertEquals(schema, seen.get(0));
    }

    @Test
    void mapRebuildsOnlyThePathThatChanged() {
        MutableJsonSchema schema = new MutableJsonSchema();
        MutableJsonSchema name = typed("string");
        schema.setProperties(Map.of("name", name));

        JsonSchema mapped = schema.map(node -> node == name ? typed("number") : node);

        assertNotSame(schema, mapped);
        assertTrue(mapped instanceof MutableJsonSchema);
        assertEquals(Map.of("properties", Map.of("name", Map.of("type", "number"))),
                JsonSchemas.toDocument(mapped));
        assertEquals(List.of("string"), name.getType());
    }

    @Test
    void mapSharesNodesItLeavesAlone() {
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setProperties(Map.of("name", typed("string")));

        assertSame(schema, schema.map(node -> node));
    }

    @Test
    void toStringRendersTheDocumentShape() {
        assertEquals("MutableJsonSchema{type=object}", typed("object").toString());
    }

    @Test
    void aSchemaThatContainsItselfIsRefused() {
        // A recursive schema is spelled with $ref, so a graph that cycles is a hand-built mistake:
        // without the guard, both the walk and the document recurse until the stack is gone.
        MutableJsonSchema schema = typed("object");
        MutableJsonSchema inner = typed("object");
        schema.setProperties(Map.of("inner", inner));
        inner.setProperties(Map.of("parent", schema));

        assertThrows(SynapseException.class, () -> JsonSchemas.toDocument(schema));
        assertThrows(SynapseException.class, () -> schema.visit(each -> {
        }));
        assertThrows(SynapseException.class, () -> schema.map(node -> node));
    }

    @Test
    void aSubSchemaReachedTwiceIsWrittenAndVisitedAtEachPath() {
        // The guard follows the path, not everything already seen: JSON has no way to share one, so
        // a node reached through two paths is written out at both and walked at both.
        MutableJsonSchema shared = typed("string");
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setProperties(Map.of("first", shared, "second", shared));

        assertEquals(Map.of("properties",
                Map.of("first", Map.of("type", "string"), "second", Map.of("type", "string"))),
                JsonSchemas.toDocument(schema));

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);
        assertEquals(List.of(schema, shared, shared), visited);
    }

    @Test
    void copyOfCarriesEveryKeyword() {
        MutableJsonSchema source = new MutableJsonSchema();
        source.setType("object");
        source.setRequired(List.of("name"));
        source.put("format", "uuid");

        MutableJsonSchema copy = MutableJsonSchema.copyOf(source);

        assertNotSame(source, copy);
        assertEquals(Set.of("type", "required", "format"), copy.keys());
        assertEquals(JsonSchemas.toDocument(source), JsonSchemas.toDocument(copy));
    }

    @Test
    void copyOfSharesSubSchemas() {
        MutableJsonSchema name = typed("string");
        MutableJsonSchema source = new MutableJsonSchema();
        source.setProperties(Map.of("name", name));

        MutableJsonSchema copy = MutableJsonSchema.copyOf(source);

        assertNotSame(source, copy);
        assertSame(name, copy.getProperties().get("name"));
    }

    @Test
    void copyOfLeavesTheOriginalAlone() {
        MutableJsonSchema source = typed("object");

        MutableJsonSchema copy = MutableJsonSchema.copyOf(source);
        copy.setType("array");

        assertEquals(List.of("object"), source.getType());
        assertEquals(List.of("array"), copy.getType());
    }

    @Test
    void copyOfRefusesABooleanSchema() {
        assertThrows(SynapseException.class, () -> MutableJsonSchema.copyOf(BooleanJsonSchema.TRUE));
    }

    private static MutableJsonSchema typed(String type) {
        MutableJsonSchema schema = new MutableJsonSchema();
        schema.setType(type);
        return schema;
    }

    /** The document of an object-form schema, as the map a test compares against. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(JsonSchema schema) {
        return (Map<String, Object>) JsonSchemas.toDocument(schema);
    }

}
