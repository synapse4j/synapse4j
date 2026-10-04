package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    @Test
    void aSchemaWithoutReferencesIsReturnedUnchanged() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("name", typed("string")))
                .build();

        JsonSchema inlined = JsonSchemas.inline(schema);

        assertNotSame(schema, inlined);
        assertEquals(schema, inlined);
    }

    @Test
    void aDefinitionReferencedTwiceIsInlinedAndDropped() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of(
                        "home", ref("#/$defs/Place"),
                        "work", ref("#/$defs/Place")))
                .setDefs(Map.of("Place", typed("string")))
                .build();

        JsonSchema inlined = JsonSchemas.inline(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("home", typed("string"), "work", typed("string")))
                .build();
        assertEquals(expected, inlined);
        assertNull(inlined.getDefs());
    }

    @Test
    void aReferenceThatClosesACycleIsKeptWithItsDefinition() {
        JsonSchema node = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", ref("#/$defs/Node")))
                .build();
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", ref("#/$defs/Node")))
                .setDefs(Map.of("Node", node))
                .build();

        JsonSchema inlined = JsonSchemas.inline(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", node))
                .setDefs(Map.of("Node", node))
                .build();
        assertEquals(expected, inlined);
    }

    @Test
    void aReferenceToTheRootIsKept() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("child", new JsonSchemaBuilder()
                        .put("anyOf", List.of(ref("#"), typed("null")))
                        .build()))
                .build();

        assertEquals(schema, JsonSchemas.inline(schema));
    }

    @Test
    void referencesInsideContainersAreInlined() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of(
                        "tags", new JsonSchemaBuilder()
                                .setType("array")
                                .setItems(ref("#/$defs/Tag"))
                                .build(),
                        "lookup", new JsonSchemaBuilder()
                                .setType("object")
                                .setAdditionalProperties(ref("#/$defs/Tag"))
                                .build()))
                .setDefs(Map.of("Tag", typed("string")))
                .build();

        JsonSchema inlined = JsonSchemas.inline(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of(
                        "tags", new JsonSchemaBuilder().setType("array").setItems(typed("string")).build(),
                        "lookup", new JsonSchemaBuilder().setType("object")
                                .setAdditionalProperties(typed("string")).build()))
                .build();
        assertEquals(expected, inlined);
        assertNull(inlined.getDefs());
    }

    @Test
    void aReferenceThatNamesNothingIsKept() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("value", ref("#/$defs/Missing")))
                .build();

        assertEquals(schema, JsonSchemas.inline(schema));
    }

    @Test
    void theArgumentIsNotModified() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("home", ref("#/$defs/Place")))
                .setDefs(Map.of("Place", typed("string")))
                .build();
        JsonSchema before = JsonSchemaBuilder.from(schema).build();

        JsonSchemas.inline(schema);

        assertEquals(before, schema);
        assertTrue(schema.getDefs().containsKey("Place"));
    }

    @Test
    void shapesOfAnswersTheFormsAKeywordTakes() {
        assertEquals(Set.of(JsonSchemas.Shape.SCHEMA_MAP), JsonSchemas.shapesOf("properties"));
        assertEquals(Set.of(JsonSchemas.Shape.SCHEMA, JsonSchemas.Shape.SCHEMA_LIST),
                JsonSchemas.shapesOf("items"));
        // A keyword the library does not model answers null — no knowledge of it, not "untyped".
        assertNull(JsonSchemas.shapesOf("type"));
        assertNull(JsonSchemas.shapesOf("x-provider-extension"));
    }

    private static JsonSchema typed(String type) {
        return new JsonSchemaBuilder().setType(type).build();
    }

    private static JsonSchema ref(String ref) {
        return new JsonSchemaBuilder().setRef(ref).build();
    }

}
