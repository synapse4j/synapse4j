package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchemaBuilder;

class FunctionToolTest {

    private final JsonCodec codec = mock(JsonCodec.class);

    /** The one value the codec decodes arguments into, whatever type is asked. */
    private record Input(String value) {
    }

    @BeforeEach
    void codecAnswersObjectSchema() {
        when(codec.generateDecodeSchema(any())).thenReturn(new JsonSchemaBuilder().setType("object").build());
    }

    // ===== factories =====

    @Test
    void signatureFactoryBuildsDeclaration() {
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, context) -> input.value(), codec);

        assertEquals("echo", tool.definition().getName());
        assertEquals("Echoes back", tool.definition().getDescription());
        assertEquals(List.of("object"), tool.definition().getInputSchema().getType());
    }

    @Test
    void scalarInputTypeRefused() {
        when(codec.generateDecodeSchema(String.class)).thenReturn(new JsonSchemaBuilder().setType("string").build());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FunctionTool.of("len", "Counts", String.class, (input, context) -> "x", codec));

        assertTrue(failure.getMessage().contains("record"));
    }

    @Test
    void handedDeclarationKeptAsIs() {
        ToolDefinition handed = new ToolDefinition("handed", "Built by hand",
                new JsonSchemaBuilder().setType("object").build());

        FunctionTool<Input, String> tool = FunctionTool.of(handed, Input.class, (input, context) -> "x", codec);

        assertSame(handed, tool.definition());
    }

    // ===== the three stages =====

    @Test
    void argumentsDecodeIntoOneValue() throws Exception {
        Input decoded = new Input("hello");
        when(codec.decode(any(), any())).thenReturn(decoded);
        StringBuilder seen = new StringBuilder();
        ChatContext context = new ChatContext();
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class, (input, ctx) -> {
            seen.append(input.value()).append('|').append(ctx == context);
            return input.value();
        }, codec);

        Object[] values = tool.resolveArguments("{\"value\":\"hello\"}", context);

        assertEquals(1, values.length);
        assertSame(decoded, values[0]);
        assertEquals("hello", tool.call(values, context));
        assertEquals("hello|true", seen.toString());
    }

    @Test
    void absentArgumentsBecomeEmptyObject() throws Exception {
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, ctx) -> "ok", codec);

        tool.resolveArguments("   ", null);
        tool.resolveArguments(null, null);

        // The stage's contract: null or blank means the model produced none, and none spells
        // "{}" — never a null the codec would meet with its own idea of the question.
        verify(codec, times(2)).decode("{}", Input.class);
    }

    @Test
    void stringValuePassedThrough() throws Exception {
        when(codec.decode(any(), any())).thenReturn(new Input("plain"));
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, context) -> "already text", codec);

        assertEquals("already text", execute(tool, "{\"value\":\"plain\"}"));
    }

    @Test
    void otherReturnsRenderedByCodec() throws Exception {
        Input decoded = new Input("x");
        when(codec.decode(any(), any())).thenReturn(decoded);
        when(codec.encode(decoded)).thenReturn("encoded");
        FunctionTool<Input, Input> tool = FunctionTool.of("self", "Returns the input", Input.class,
                (input, context) -> input, codec);

        assertEquals("encoded", execute(tool, "{\"value\":\"x\"}"));
    }

    @Test
    void nullReturnRendersJsonNull() throws Exception {
        when(codec.decode(any(), any())).thenReturn(new Input("x"));
        when(codec.encode(null)).thenReturn("null");
        FunctionTool<Input, String> tool = FunctionTool.of("maybe", "Sometimes silent", Input.class,
                (input, context) -> null, codec);

        assertEquals("null", execute(tool, "{\"value\":\"x\"}"));

        // the null return is the codec's to render, not an answer the tool supplies on its own
        verify(codec).encode(null);
    }

    @Test
    void executorFailureArrivesAsItself() {
        IllegalStateException original = new IllegalStateException("boom");
        when(codec.decode(any(), any())).thenReturn(new Input("x"));
        FunctionTool<Input, String> tool = FunctionTool.of("fail", "Always fails", Input.class, (input, context) -> {
            throw original;
        }, codec);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tool.execute("{}", null));
        assertSame(original, thrown);
    }

    @Test
    void emptyDocumentRefusedBeforeExecutor() {
        FunctionTool<Input, String> tool = FunctionTool.of("maybe", "Sometimes silent", Input.class,
                (input, context) -> "ran", codec);

        SynapseException thrown = assertThrows(SynapseException.class, () -> tool.execute("{}", null));
        assertTrue(thrown.getMessage().contains("null document"));
    }

    // ===== harness =====

    private String execute(FunctionTool<?, ?> tool, String arguments) throws Exception {
        List<ContentPart> parts = tool.execute(arguments, null);
        assertEquals(1, parts.size());
        return ((TextPart) parts.get(0)).getText();
    }

}
