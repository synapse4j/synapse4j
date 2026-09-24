package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ToolDefinition;
import org.junit.jupiter.api.Test;

class FunctionToolTest {

    private final ToolDefinition definition = new ToolDefinition("weather", "Looks up the weather",
            "{\"type\":\"object\"}");

    @Test
    void carriesTheDeclarationGiven() {
        FunctionTool tool = FunctionTool.of(definition);

        assertSame(definition, tool.definition());
    }

    @Test
    void runsTheExecutorWithWhatItWasGiven() throws Exception {
        ChatContext context = new ChatContext();
        StringBuilder seen = new StringBuilder();
        FunctionTool tool = FunctionTool.of(definition, (arguments, ctx) -> {
            seen.append(arguments).append('|').append(ctx == context);
            return "sunny";
        });

        String result = tool.execute("{\"city\":\"Oslo\"}", context);

        assertEquals("sunny", result);
        assertEquals("{\"city\":\"Oslo\"}|true", seen.toString());
    }

    @Test
    void carriesNullContextThroughToTheExecutor() throws Exception {
        FunctionTool tool = FunctionTool.of(definition, (arguments, ctx) -> {
            assertNull(ctx);
            return "ok";
        });

        assertEquals("ok", tool.execute("{}", null));
    }

    @Test
    void exposesTheExecutorItWasBuiltWith() {
        FunctionTool.Executor executor = (arguments, ctx) -> "done";
        FunctionTool tool = FunctionTool.of(definition, executor);

        assertSame(executor, tool.executor());
    }

    @Test
    void declarationOnlyToolHasNoExecutorAndRefusesToRun() {
        FunctionTool tool = FunctionTool.of(definition);

        assertNull(tool.executor());
        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> tool.execute("{}", null));
        assertEquals("tool 'weather' was declared without an executor", failure.getMessage());
    }

    @Test
    void executorFailureCarriesThroughUnchanged() {
        Exception original = new Exception("the weather service is down");
        FunctionTool tool = FunctionTool.of(definition, (arguments, ctx) -> {
            throw original;
        });

        Exception thrown = assertThrows(Exception.class, () -> tool.execute("{}", null));

        assertSame(original, thrown);
    }

    @Test
    void factoriesRefuseMissingParts() {
        assertThrows(NullPointerException.class, () -> FunctionTool.of(null));
        assertThrows(NullPointerException.class, () -> FunctionTool.of(null, (arguments, ctx) -> "x"));
        assertThrows(NullPointerException.class, () -> FunctionTool.of(definition, null));
    }

}
