package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InlineJsonSchemaCustomizerTest {

    private final InlineJsonSchemaCustomizer customizer = new InlineJsonSchemaCustomizer();

    @Test
    void schemaWithoutReferencesReturnedUnchanged() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("name", typed("string")))
                .build();

        JsonSchema inlined = customizer.customize(schema);

        assertNotSame(schema, inlined);
        assertEquals(schema, inlined);
    }

    @Test
    void referencedTwiceInlinedAndDropped() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of(
                        "home", ref("#/$defs/Place"),
                        "work", ref("#/$defs/Place")))
                .setDefs(Map.of("Place", typed("string")))
                .build();

        JsonSchema inlined = customizer.customize(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("home", typed("string"), "work", typed("string")))
                .build();
        assertEquals(expected, inlined);
        assertNull(inlined.getDefs());
    }

    @Test
    void booleanDefinitionInlined() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of(
                        "anything", ref("#/$defs/Any"),
                        "nothing", ref("#/$defs/None")))
                .setDefs(Map.of("Any", BooleanJsonSchema.TRUE, "None", BooleanJsonSchema.FALSE))
                .build();

        JsonSchema inlined = customizer.customize(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("anything", BooleanJsonSchema.TRUE, "nothing", BooleanJsonSchema.FALSE))
                .build();
        assertEquals(expected, inlined);
        assertNull(inlined.getDefs());
    }

    @Test
    void cycleClosingReferenceKept() {
        JsonSchema node = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", ref("#/$defs/Node")))
                .build();
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", ref("#/$defs/Node")))
                .setDefs(Map.of("Node", node))
                .build();

        JsonSchema inlined = customizer.customize(schema);

        JsonSchema expected = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("next", node))
                .setDefs(Map.of("Node", node))
                .build();
        assertEquals(expected, inlined);
    }

    @Test
    void referenceToRootKept() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("child", new JsonSchemaBuilder()
                        .put("anyOf", List.of(ref("#"), typed("null")))
                        .build()))
                .build();

        assertEquals(schema, customizer.customize(schema));
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

        JsonSchema inlined = customizer.customize(schema);

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
    void referenceNamingNothingKept() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("value", ref("#/$defs/Missing")))
                .build();

        assertEquals(schema, customizer.customize(schema));
    }

    @Test
    void unresolvedReferenceFormKept() {
        JsonSchema nested = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("inner", ref("#/properties/nested/$defs/Inner")))
                .setDefs(Map.of("Inner", typed("string")))
                .build();
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("nested", nested))
                .build();

        assertEquals(schema, customizer.customize(schema));
    }

    @Test
    void argumentNotModified() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setProperties(Map.of("home", ref("#/$defs/Place")))
                .setDefs(Map.of("Place", typed("string")))
                .build();
        JsonSchema before = JsonSchemaBuilder.from(schema).build();

        customizer.customize(schema);

        assertEquals(before, schema);
        assertTrue(schema.getDefs().containsKey("Place"));
    }

    private static JsonSchema typed(String type) {
        return new JsonSchemaBuilder().setType(type).build();
    }

    private static JsonSchema ref(String ref) {
        return new JsonSchemaBuilder().setRef(ref).build();
    }

}
