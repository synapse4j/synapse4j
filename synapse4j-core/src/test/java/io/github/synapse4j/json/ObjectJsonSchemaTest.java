package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ObjectJsonSchemaTest {

    @Test
    void newSchemaCarriesNothing() {
        JsonSchema schema = built();

        assertTrue(schema.keys().isEmpty());
        assertNull(schema.get("format", Object.class));
    }

    @Test
    void anEmptyKeywordIsNotTheSameAsAbsent() {
        JsonSchema absent = built();
        JsonSchema empty = new JsonSchemaBuilder()
                .setRequired(List.of())
                .setProperties(Map.of())
                .build();

        assertNull(absent.getRequired());
        assertNull(absent.getProperties());
        assertEquals(List.of(), empty.getRequired());
        assertEquals(Map.of(), empty.getProperties());
    }

    @Test
    void readingDoesNotChangeTheSchema() {
        JsonSchema schema = built();

        schema.getType();
        schema.getProperties();
        schema.getRequired();
        schema.getDefs();
        schema.get("items", JsonSchema.class);

        assertTrue(schema.keys().isEmpty());
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
    void theTypeFormFollowsTheSetterThatWasUsed() {
        // Set as a string, stored as a string; set as a list, stored as an array — a one-element
        // list is not folded into the string form, which is why the two schemas are not equal.
        JsonSchema single = new JsonSchemaBuilder().setType("object").build();
        JsonSchema array = new JsonSchemaBuilder().setType(List.of("object")).build();

        assertNotEquals(single, array);
        assertEquals(List.of("object"), single.getType());
        assertEquals(List.of("object"), array.getType());
    }

    @Test
    void nestedSchemasStaySchemas() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setProperties(Map.of("name", typed("string")))
                .setItems(typed("number"))
                .setDefs(Map.of("D", typed("boolean")))
                .setAdditionalProperties(BooleanJsonSchema.FALSE)
                .build();

        assertEquals(typed("string"), schema.getProperties().get("name"));
        assertEquals(typed("number"), schema.get("items", JsonSchema.class));
        assertEquals(typed("boolean"), schema.getDefs().get("D"));
        assertEquals(BooleanJsonSchema.FALSE, schema.getAdditionalProperties());
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
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema", String.class));
        assertEquals("object", schema.get("type"));
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
    }

    @Test
    void anExplicitNullIsCarried() {
        JsonSchema schema = new JsonSchemaBuilder().put("const", null).build();

        assertEquals(Set.of("const"), schema.keys());
        assertNull(schema.get("const", Object.class));
    }

    @Test
    void everySchemaValuedKeywordIsWalked() {
        JsonSchema schema = new JsonSchemaBuilder()
                .put("allOf", List.of(typed("string")))
                .put("not", typed("null"))
                .put("propertyNames", new JsonSchemaBuilder().put("pattern", "^[a-z]+$").build())
                .put("properties", Map.of("name", typed("string")))
                .put("prefixItems", List.of(typed("number")))
                .build();

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);

        assertEquals(6, visited.size());
    }

    @Test
    void booleanValuesAreCarriedAsSchemas() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setAdditionalProperties(BooleanJsonSchema.FALSE)
                .put("not", BooleanJsonSchema.TRUE)
                .build();

        assertEquals(BooleanJsonSchema.FALSE, schema.getAdditionalProperties());
        assertEquals(BooleanJsonSchema.TRUE, schema.get("not", JsonSchema.class));
    }

    @Test
    void anItemsListIsWalked() {
        // 2020-12 makes "items" a single schema; draft-07 allowed an array. The array is read and
        // walked as a list of schemas, which a typed read as a single schema does not answer.
        JsonSchema schema = new JsonSchemaBuilder()
                .put("items", List.of(typed("string"), typed("number")))
                .build();

        assertNull(schema.get("items", JsonSchema.class));
        assertEquals(List.of(typed("string"), typed("number")), schema.getList("items", JsonSchema.class));

        List<JsonSchema> visited = new ArrayList<>();
        schema.visit(visited::add);

        assertEquals(3, visited.size());
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
        assertEquals(new JsonSchemaBuilder().setProperties(Map.of("name", typed("number"))).build(), mapped);
        assertEquals(List.of("string"), name.getType());
    }

    @Test
    void mapSharesNodesItLeavesAlone() {
        JsonSchema schema = new JsonSchemaBuilder().setProperties(Map.of("name", typed("string"))).build();

        assertSame(schema, schema.map(node -> node));
    }

    @Test
    void toStringRendersTheValueItCarries() {
        assertEquals("{type=object}", typed("object").toString());
        // A sub-schema renders as its own value, so a nested schema reads as one document.
        assertEquals("{properties={name={type=string}}}",
                new JsonSchemaBuilder().setProperties(Map.of("name", typed("string"))).build().toString());
    }

    @Test
    void toStringRendersTheBooleanValue() {
        assertEquals("true", BooleanJsonSchema.TRUE.toString());
        assertEquals("false", BooleanJsonSchema.FALSE.toString());
    }

    @Test
    void twoSchemasCarryingTheSameKeywordsAreEqual() {
        JsonSchema one = new JsonSchemaBuilder().setType("object")
                .setProperties(Map.of("name", typed("string"))).build();
        JsonSchema other = new JsonSchemaBuilder().setType("object")
                .setProperties(Map.of("name", typed("string"))).build();

        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
        assertNotEquals(one, typed("object"));
        assertNotEquals(one, BooleanJsonSchema.TRUE);
    }

    @Test
    void aSubSchemaReachedTwiceIsVisitedAtEachPath() {
        // A node reached through two paths is walked at each of them.
        JsonSchema shared = typed("string");
        JsonSchema schema = new JsonSchemaBuilder().setProperties(Map.of("first", shared, "second", shared)).build();

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

    @SuppressWarnings("unchecked")
    private static List<Object> rawList(JsonSchema schema, String keyword) {
        return (List<Object>) schema.get(keyword);
    }

}
