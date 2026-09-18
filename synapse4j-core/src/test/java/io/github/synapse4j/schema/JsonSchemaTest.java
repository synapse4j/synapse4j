package io.github.synapse4j.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonSchemaTest {

    @Test
    void newSchemaCarriesNothing() {
        JsonSchema schema = new JsonSchema();

        assertTrue(schema.getType().isEmpty());
        assertTrue(schema.getProperties().isEmpty());
        assertTrue(schema.getRequired().isEmpty());
        assertNull(schema.getItems());
        assertNull(schema.getAdditionalProperties());
        assertTrue(schema.getEnumValues().isEmpty());
        assertTrue(schema.getDefs().isEmpty());
        assertNull(schema.getRef());
        assertTrue(schema.getAnyOf().isEmpty());
        assertTrue(schema.getOneOf().isEmpty());
        assertTrue(schema.getAllOf().isEmpty());
        assertNull(schema.getTitle());
        assertNull(schema.getDescription());
        assertTrue(schema.getExtras().isEmpty());
        assertTrue(schema.toMap().isEmpty());
    }

    @Test
    void aSingleTypeIsWrittenAsAStringAndSeveralAsAnArray() {
        assertEquals(Map.of("type", "object"), typed("object").toMap());

        JsonSchema nullable = new JsonSchema();
        nullable.setType(List.of("string", "null"));

        assertEquals(Map.of("type", List.of("string", "null")), nullable.toMap());
    }

    @Test
    void onlyWhatIsSetIsWritten() {
        JsonSchema schema = typed("object");

        assertEquals(Map.of("type", "object"), schema.toMap());
    }

    @Test
    void nestedSchemasAreWrittenAsMaps() {
        JsonSchema schema = new JsonSchema();
        schema.getProperties().put("name", typed("string"));
        schema.setItems(typed("number"));
        schema.getDefs().put("D", typed("boolean"));
        schema.getAnyOf().add(typed("null"));

        Map<String, Object> map = schema.toMap();

        assertEquals(Map.of("name", Map.of("type", "string")), map.get("properties"));
        assertEquals(Map.of("type", "number"), map.get("items"));
        assertEquals(Map.of("D", Map.of("type", "boolean")), map.get("$defs"));
        assertEquals(List.of(Map.of("type", "null")), map.get("anyOf"));
    }

    @Test
    void theOpenPartIsWrittenBesideTheFields() {
        JsonSchema schema = typed("object");
        schema.getExtras().put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.getExtras().put("format", "uuid");

        Map<String, Object> map = schema.toMap();

        assertEquals("https://json-schema.org/draft/2020-12/schema", map.get("$schema"));
        assertEquals("uuid", map.get("format"));
        assertEquals("object", map.get("type"));
    }

    @Test
    void aFieldWinsOverTheOpenPart() {
        JsonSchema schema = typed("object");
        schema.getExtras().put("type", "string");

        assertEquals("object", schema.toMap().get("type"));
    }

    @Test
    void everyModelledKeywordSurvivesARoundTrip() {
        JsonSchema schema = new JsonSchema();
        schema.setType(List.of("string", "null"));
        schema.setTitle("WeatherQuery");
        schema.setDescription("A request for the weather.");
        schema.setAdditionalProperties(false);
        schema.getRequired().add("locations");
        schema.getEnumValues().add("metric");
        schema.getProperties().put("locations", typed("array"));
        schema.setItems(typed("string"));
        schema.getDefs().put("Location", typed("object"));
        schema.setRef("#/$defs/Location");
        schema.getAnyOf().add(typed("string"));
        schema.getOneOf().add(typed("number"));
        schema.getAllOf().add(typed("boolean"));
        schema.getExtras().put("format", "uuid");

        JsonSchema read = JsonSchema.fromMap(schema.toMap());

        assertEquals(schema, read);
        assertEquals(schema.toMap(), read.toMap());
    }

    @Test
    void fromMapCarriesKeywordsItDoesNotModel() {
        JsonSchema schema = JsonSchema.fromMap(Map.of(
                "type", "object",
                "const", 5,
                "$schema", "https://json-schema.org/draft/2020-12/schema"));

        assertEquals(List.of("object"), schema.getType());
        assertEquals(5, schema.getExtras().get("const"));
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.getExtras().get("$schema"));
        assertEquals(Map.of("type", "object", "const", 5,
                "$schema", "https://json-schema.org/draft/2020-12/schema"), schema.toMap());
    }

    @Test
    void fromMapCarriesModelledKeywordsInUnexpectedShapes() {
        JsonSchema schema = JsonSchema.fromMap(Map.of(
                "additionalProperties", Map.of("type", "string"),
                "items", List.of(Map.of("type", "string"))));

        assertNull(schema.getAdditionalProperties());
        assertNull(schema.getItems());
        assertEquals(Map.of("type", "string"), schema.getExtras().get("additionalProperties"));
        assertEquals(List.of(Map.of("type", "string")), schema.getExtras().get("items"));
        assertEquals(Map.of("additionalProperties", Map.of("type", "string"),
                "items", List.of(Map.of("type", "string"))), schema.toMap());
    }

    @Test
    void subSchemasReturnsEveryNestedSchema() {
        JsonSchema schema = new JsonSchema();
        JsonSchema property = typed("string");
        JsonSchema item = typed("number");
        schema.getProperties().put("name", property);
        schema.setItems(item);

        assertEquals(2, schema.subSchemas().size());
        assertTrue(schema.subSchemas().contains(property));
        assertTrue(schema.subSchemas().contains(item));
    }

    @Test
    void visitReachesEverySchemaAndCanChangeItInPlace() {
        JsonSchema schema = new JsonSchema();
        schema.getProperties().put("name", typed("string"));
        schema.setItems(typed("object"));

        List<JsonSchema> seen = new ArrayList<>();
        schema.visit(seen::add);
        assertEquals(3, seen.size());

        schema.visit(each -> each.setAdditionalProperties(false));

        schema.visit(each -> assertEquals(false, each.getAdditionalProperties()));
    }

    @Test
    void toStringRendersTheDocumentShape() {
        assertEquals("JsonSchema{type=object}", typed("object").toString());
    }

    private static JsonSchema typed(String type) {
        JsonSchema schema = new JsonSchema();
        schema.setType(type);
        return schema;
    }

}
