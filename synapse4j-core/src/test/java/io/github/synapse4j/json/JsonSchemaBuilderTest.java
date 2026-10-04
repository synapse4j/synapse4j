package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.synapse4j.exception.SynapseException;
import org.junit.jupiter.api.Test;

class JsonSchemaBuilderTest {

    @Test
    void fromCarriesEveryKeyword() {
        JsonSchema source = new JsonSchemaBuilder()
                .setType("object")
                .setRequired(List.of("name"))
                .put("format", "uuid")
                .build();

        JsonSchema copy = JsonSchemaBuilder.from(source).build();

        assertNotSame(source, copy);
        assertEquals(Set.of("type", "required", "format"), copy.keys());
        assertEquals(source, copy);
    }

    @Test
    void fromSharesSubSchemas() {
        JsonSchema name = new JsonSchemaBuilder().setType("string").build();
        JsonSchema source = new JsonSchemaBuilder().setProperties(Map.of("name", name)).build();

        JsonSchema copy = JsonSchemaBuilder.from(source).build();

        assertNotSame(source, copy);
        assertSame(name, copy.getProperties().get("name"));
    }

    @Test
    void fromLeavesTheOriginalAlone() {
        JsonSchema source = new JsonSchemaBuilder().setType("object").build();

        JsonSchema copy = JsonSchemaBuilder.from(source).setType("array").build();

        assertEquals(List.of("object"), source.getType());
        assertEquals(List.of("array"), copy.getType());
    }

    @Test
    void fromRefusesABooleanSchema() {
        assertThrows(SynapseException.class, () -> JsonSchemaBuilder.from(BooleanJsonSchema.TRUE));
    }

    @Test
    void settingAModelledKeywordToNullClearsIt() {
        JsonSchema schema = new JsonSchemaBuilder()
                .setType("object")
                .setTitle("WeatherQuery")
                .setTitle(null)
                .build();

        assertEquals(Set.of("type"), schema.keys());
        assertNull(schema.getTitle());
    }

}
