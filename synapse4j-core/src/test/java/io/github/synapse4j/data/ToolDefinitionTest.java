package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.tool.ToolDefinition;

class ToolDefinitionTest {

    @Test
    void declarationKeepsFrozenExtras() {
        ProviderExtras extras = new ProviderExtras().put("strict", true);
        ToolDefinition definition = new ToolDefinition("get_weather", null, null, null, extras);

        extras.put("later", 1);

        assertTrue(definition.getExtras().contains("strict"));
        assertFalse(definition.getExtras().contains("later"));
        assertThrows(UnsupportedOperationException.class, () -> definition.getExtras().put("x", 1));
    }

}
