package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.tool.ToolDefinition;

class ToolDefinitionTest {

    @Test
    void everyToolGetsItsOwnExtrasBag() {
        ToolDefinition one = new ToolDefinition();
        ToolDefinition two = new ToolDefinition();

        one.getExtras().put("strict", true);

        assertTrue(two.getExtras().isEmpty());
    }

}
