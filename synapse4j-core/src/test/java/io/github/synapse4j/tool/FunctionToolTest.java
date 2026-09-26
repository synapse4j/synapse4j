package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.json.AbstractJsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionToolTest {

    private final FakeCodec codec = new FakeCodec();

    /** The one value the codec decodes arguments into, whatever type is asked. */
    private record Input(String value) {
    }

    // ===== factories =====

    @Test
    void signatureFactoryBuildsTheDeclarationFromTheType() {
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, context) -> input.value(), codec);

        assertEquals("echo", tool.definition().getName());
        assertEquals("Echoes back", tool.definition().getDescription());
        assertEquals("encoded", tool.definition().getInputSchema());
        assertTrue(codec.generatedFor.contains(Input.class));
        assertSame(Input.class, lastOf(codec.generatedFor));
    }

    @Test
    void scalarInputTypeIsRefusedAtTheFactory() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FunctionTool.of("len", "Counts", String.class, (input, context) -> "x", codec));

        assertTrue(failure.getMessage().contains("record"));
    }

    @Test
    void signatureFactoryRefusesMissingParts() {
        assertThrows(NullPointerException.class,
                () -> FunctionTool.of(null, "d", Input.class, (in, ctx) -> "x", codec));
        assertThrows(NullPointerException.class,
                () -> FunctionTool.of("n", null, Input.class, (in, ctx) -> "x", codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of("n", "d", null, (in, ctx) -> "x", codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of("n", "d", Input.class, null, codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of("n", "d", Input.class, (in, ctx) -> "x", null));
    }

    @Test
    void handedDeclarationKeptAsIsAndStillDemandsTheRest() {
        ToolDefinition handed = new ToolDefinition("handed", "Built by hand", "{\"type\":\"object\"}");

        FunctionTool<Input, String> tool = FunctionTool.of(handed, Input.class, (input, context) -> "x", codec);
        assertSame(handed, tool.definition());
        assertTrue(codec.encoded.isEmpty());

        assertThrows(NullPointerException.class,
                () -> FunctionTool.of((ToolDefinition) null, Input.class, (in, ctx) -> "x", codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of(handed, null, (in, ctx) -> "x", codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of(handed, Input.class, null, codec));
        assertThrows(NullPointerException.class, () -> FunctionTool.of(handed, Input.class, (in, ctx) -> "x", null));
    }

    // ===== the three stages =====

    @Test
    void argumentsDecodeIntoOneValueAndReachTheExecutor() throws Exception {
        codec.decoded = new Input("hello");
        StringBuilder seen = new StringBuilder();
        ChatContext context = new ChatContext();
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class, (input, ctx) -> {
            seen.append(input.value()).append('|').append(ctx == context);
            return input.value();
        }, codec);

        Object[] values = tool.resolveArguments("{\"value\":\"hello\"}", context);

        assertEquals(1, values.length);
        assertSame(codec.decoded, values[0]);
        assertTrue(codec.decodedFor.contains(Input.class));

        assertEquals("hello", tool.call(values, context));
        assertEquals("hello|true", seen.toString());
    }

    @Test
    void argumentsThatNeverArrivedDecodeAsAnEmptyObject() throws Exception {
        codec.decoded = new Input(null);
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, ctx) -> "ok", codec);

        Object[] blank = tool.resolveArguments("   ", null);
        Object[] missing = tool.resolveArguments(null, null);

        // The stage's contract: null or blank means the model produced none, and none spells
        // "{}" — never a null the codec would meet with its own idea of the question.
        assertEquals(List.of("{}", "{}"), codec.decodedFrom);
        assertEquals(1, blank.length);
        assertEquals(1, missing.length);
    }

    @Test
    void aStringValueReachesTheModelAsItself() throws Exception {
        codec.decoded = new Input("plain");
        FunctionTool<Input, String> tool = FunctionTool.of("echo", "Echoes back", Input.class,
                (input, context) -> "already text", codec);

        assertEquals("already text", execute(tool, "{\"value\":\"plain\"}"));
    }

    @Test
    void otherReturnsAreRenderedByTheCodec() throws Exception {
        codec.decoded = new Input("x");
        FunctionTool<Input, Input> tool = FunctionTool.of("self", "Returns the input", Input.class,
                (input, context) -> input, codec);

        assertEquals("encoded", execute(tool, "{\"value\":\"x\"}"));
        assertInstanceOf(Input.class, lastOf(codec.encoded));
    }

    @Test
    void aNullReturnRendersAsJsonNull() throws Exception {
        codec.decoded = new Input("x");
        FunctionTool<Input, String> tool = FunctionTool.of("maybe", "Sometimes silent", Input.class,
                (input, context) -> null, codec);

        assertEquals("encoded", execute(tool, "{\"value\":\"x\"}"));
        assertNull(lastOf(codec.encoded));
    }

    @Test
    void executorFailureArrivesAsItself() {
        IllegalStateException original = new IllegalStateException("boom");
        FunctionTool<Input, String> tool = FunctionTool.of("fail", "Always fails", Input.class, (input, context) -> {
            throw original;
        }, codec);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tool.execute("{}", null));
        assertSame(original, thrown);
    }

    // ===== declaration only =====

    @Test
    void declarationOnlyCarriesNothingBehindIt() throws Exception {
        ToolDefinition declaration = new ToolDefinition("bare", "Just the words", "{\"type\":\"object\"}");

        FunctionTool<Object, Object> tool = FunctionTool.of(declaration);

        assertSame(declaration, tool.definition());
        assertNull(tool.executor());
        assertEquals(0, tool.resolveArguments(null, null).length);

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> tool.execute("{}", null));
        assertEquals("tool 'bare' was declared without an executor", failure.getMessage());
    }

    @Test
    void declarationOnlyFactoryRefusesNull() {
        assertThrows(NullPointerException.class, () -> FunctionTool.of((ToolDefinition) null));
    }

    // ===== harness =====

    private <T> T lastOf(List<T> list) {
        return list.get(list.size() - 1);
    }

    private String execute(FunctionTool<?, ?> tool, String arguments) throws Exception {
        List<ContentPart> parts = tool.execute(arguments, null);
        assertEquals(1, parts.size());
        return ((TextPart) parts.get(0)).getText();
    }

    /**
     * A codec that answers what a test sets up: one decoded value, type-aware schema shapes —
     * String is a scalar so the factory's object check has something to refuse.
     */
    private static class FakeCodec extends AbstractJsonCodec {

        /** What every decode answers, whatever type is asked. */
        private Object decoded;

        /** The types decode was asked for, in order. */
        private final List<Type> decodedFor = new ArrayList<>();

        /** The text decode was handed, in order. */
        private final List<String> decodedFrom = new ArrayList<>();

        /** Everything encode was asked to render, in order. */
        private final List<Object> encoded = new ArrayList<>();

        /** The types generateDecodeSchema was asked for, in order. */
        private final List<Type> generatedFor = new ArrayList<>();

        @Override
        public JsonSchema generateEncodeSchema(Type type) {
            return new JsonSchema();
        }

        @Override
        public JsonSchema generateDecodeSchema(Type type) {
            generatedFor.add(type);
            JsonSchema schema = new JsonSchema();
            if (type == String.class) {
                schema.setType("string");
            } else if (type == int.class || type == Integer.class) {
                schema.setType("integer");
            } else {
                schema.setType("object");
            }
            return schema;
        }

        @Override
        protected String encodeValue(Object value) {
            encoded.add(value);
            return "encoded";
        }

        @SuppressWarnings("unchecked")
        @Override
        protected <T> T decodeValue(String json, Type type) {
            decodedFor.add(type);
            decodedFrom.add(json);
            return (T) decoded;
        }

        @Override
        public JsonWriter writer(OutputStream out) {
            throw new UnsupportedOperationException("these tests never write a document");
        }

        @Override
        public JsonReader reader(InputStream in) {
            throw new UnsupportedOperationException("these tests never read a document");
        }

    }

}
