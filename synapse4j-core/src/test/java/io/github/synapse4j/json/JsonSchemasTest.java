package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    @Test
    void aSchemaWithoutReferencesIsReturnedUnchanged() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of("type", "string"))));

        JsonSchema inlined = JsonSchemas.inline(schema);

        assertNotSame(schema, inlined);
        assertEquals(JsonSchemas.toDocument(schema), JsonSchemas.toDocument(inlined));
    }

    @Test
    void aDefinitionReferencedTwiceIsInlinedAndDropped() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of(
                        "home", Map.of("$ref", "#/$defs/Place"),
                        "work", Map.of("$ref", "#/$defs/Place")),
                "$defs", Map.of("Place", Map.of("type", "string"))));

        JsonSchema inlined = JsonSchemas.inline(schema);

        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of(
                        "home", Map.of("type", "string"),
                        "work", Map.of("type", "string"))),
                JsonSchemas.toDocument(inlined));
        assertNull(inlined.getDefs());
    }

    @Test
    void aReferenceThatClosesACycleIsKeptWithItsDefinition() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of("next", Map.of("$ref", "#/$defs/Node")),
                "$defs", Map.of("Node", Map.of(
                        "type", "object",
                        "properties", Map.of("next", Map.of("$ref", "#/$defs/Node"))))));

        JsonSchema inlined = JsonSchemas.inline(schema);

        Map<String, Object> node = Map.of(
                "type", "object",
                "properties", Map.of("next", Map.of("$ref", "#/$defs/Node")));
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("next", node),
                "$defs", Map.of("Node", node)),
                JsonSchemas.toDocument(inlined));
    }

    @Test
    void aReferenceToTheRootIsKept() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of("child", Map.of(
                        "anyOf", List.of(Map.of("$ref", "#"), Map.of("type", "null"))))));

        assertEquals(JsonSchemas.toDocument(schema), JsonSchemas.toDocument(JsonSchemas.inline(schema)));
    }

    @Test
    void referencesInsideContainersAreInlined() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of(
                        "tags", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/Tag")),
                        "lookup", Map.of("type", "object", "additionalProperties", Map.of("$ref", "#/$defs/Tag"))),
                "$defs", Map.of("Tag", Map.of("type", "string"))));

        JsonSchema inlined = JsonSchemas.inline(schema);

        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of(
                        "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                        "lookup", Map.of("type", "object", "additionalProperties", Map.of("type", "string")))),
                JsonSchemas.toDocument(inlined));
        assertNull(inlined.getDefs());
    }

    @Test
    void aReferenceThatNamesNothingIsKept() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("$ref", "#/$defs/Missing"))));

        assertEquals(JsonSchemas.toDocument(schema), JsonSchemas.toDocument(JsonSchemas.inline(schema)));
    }

    @Test
    void theArgumentIsNotModified() {
        JsonSchema schema = JsonSchemas.fromDocument(Map.of(
                "type", "object",
                "properties", Map.of("home", Map.of("$ref", "#/$defs/Place")),
                "$defs", Map.of("Place", Map.of("type", "string"))));
        Object before = JsonSchemas.toDocument(schema);

        JsonSchemas.inline(schema);

        assertEquals(before, JsonSchemas.toDocument(schema));
        assertTrue(schema.getDefs().containsKey("Place"));
    }

}
