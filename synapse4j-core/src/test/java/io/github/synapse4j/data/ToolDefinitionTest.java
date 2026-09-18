package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ToolDefinitionTest {

    @Test
    void newToolHasNoNameDescriptionOrSchema() {
        ToolDefinition tool = new ToolDefinition();

        assertNull(tool.getName());
        assertNull(tool.getDescription());
        assertNull(tool.getInputSchema());
        assertTrue(tool.getExtras().isEmpty());
    }

    @Test
    void allArgumentsConstructorFillsEveryField() {
        ToolDefinition tool = new ToolDefinition("get_weather", "Looks up the weather", "{\"type\":\"object\"}");

        assertEquals("get_weather", tool.getName());
        assertEquals("Looks up the weather", tool.getDescription());
        assertEquals("{\"type\":\"object\"}", tool.getInputSchema());
    }

    @Test
    void everyToolGetsItsOwnExtrasBag() {
        ToolDefinition one = new ToolDefinition();
        ToolDefinition two = new ToolDefinition();

        one.getExtras().put("strict", true);

        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void extrasCarryProviderSpecificFields() {
        ToolDefinition tool = new ToolDefinition("get_weather", "Looks up the weather", "{}");

        tool.getExtras().put("cache_control", Map.of("type", "ephemeral"));

        assertEquals(Map.of("type", "ephemeral"), tool.getExtras().get("cache_control"));
    }

    @Test
    void equalityAndHashCodeCoverEveryField() {
        ToolDefinition one = new ToolDefinition("get_weather", "Looks up the weather", "{}");
        ToolDefinition two = new ToolDefinition("get_weather", "Looks up the weather", "{}");

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.setDescription("Something else");
        assertNotEquals(one, two);
        two.setDescription("Looks up the weather");

        two.setInputSchema("{\"type\":\"array\"}");
        assertNotEquals(one, two);
        two.setInputSchema("{}");

        two.getExtras().put("strict", true);
        assertNotEquals(one, two);
    }

    @Test
    void toStringMentionsTheFieldsAndTheExtras() {
        String rendered = new ToolDefinition("get_weather", "Looks up the weather", "{}").toString();

        assertTrue(rendered.contains("name=get_weather"));
        assertTrue(rendered.contains("description=Looks up the weather"));
        assertTrue(rendered.contains("extras="));
    }

}
