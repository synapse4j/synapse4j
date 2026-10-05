package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MethodToolTest {

    private final JsonCodec codec = mock(JsonCodec.class);

    /** The reader that turns a method into a tool when the application names it by hand. */
    private final MethodTools reader = new MethodTools(codec);

    @BeforeEach
    void codecAnswersAPlainSchema() {
        // What the codec answers is the test's to choose; MethodTool only reacts to it. This default
        // lists the value as required, so a parameter is required unless the test says otherwise.
        when(codec.generateDecodeSchema(any()))
                .thenReturn(new JsonSchemaBuilder().setType("object").setRequired(List.of("value")).build());
        // Every model-supplied value goes through the codec, so the identity answer stands in for a
        // codec that hands a fitting value back; a test only stubs convert where it wants another.
        when(codec.convert(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ===== factories and construction =====

    @Test
    void instanceMethodNeedsATarget() {
        Method instance = method("instanceGreet", String.class);

        // A static method's acceptance is every other test's subject; what only this one pins is the
        // refusal an instance method gets without a target.
        SynapseException refused = assertThrows(SynapseException.class,
                () -> reader.of("n", "d", instance, null));
        assertTrue(refused.getMessage().contains("instance method"));
    }

    @Test
    void aResolutionThatNamesNothingIsRefused() {
        ToolMethodSpec spec = resolutionOf(method("ship", String.class, CurrentUser.class), null);

        SynapseException unnamedTool = assertThrows(SynapseException.class, () -> new MethodTool(spec, codec));
        assertTrue(unnamedTool.getMessage().contains("no name for the tool"));

        spec.setName("ship");
        spec.getParameters().get(1).setName("");

        SynapseException unnamedParameter = assertThrows(SynapseException.class,
                () -> new MethodTool(spec, codec));
        assertTrue(unnamedParameter.getMessage().contains("no name to declare"));

        spec.getParameters().get(1).setName("to");
        spec.getParameters().get(0).setName("to");

        SynapseException doubled = assertThrows(SynapseException.class, () -> new MethodTool(spec, codec));
        assertTrue(doubled.getMessage().contains("names two parameters"));
    }

    @Test
    void signatureBuildsTheDeclaration() {
        ToolDefinition definition = reader.of("weather", "Looks up weather", method("take", String.class), null)
                .definition();

        assertEquals("weather", definition.getName());
        assertEquals("Looks up weather", definition.getDescription());
        assertTrue(definition.getInputSchema().getProperties().containsKey("message"));
        assertEquals(List.of("message"), definition.getInputSchema().getRequired());
    }

    @Test
    void chatContextParameterStaysOutOfTheSchema() {
        JsonSchema schema = reader.of("ctx", "Takes the context", method("withContext", ChatContext.class), null)
                .definition()
                .getInputSchema();

        assertFalse(schema.keys().contains("properties"));
        assertFalse(schema.keys().contains("required"));
    }

    @Test
    void privateMethodRunsOnceHandedOver() throws Exception {
        MethodTool tool = reader.of("secret", "A private method", Target.class.getDeclaredMethod("secret"), null);

        assertEquals("secret!", execute(tool, ""));
    }

    // ===== resolveArguments =====

    @Test
    void chatContextParameterReceivesTheConversation() throws Exception {
        MethodTool tool = reader.of("ctx", "Takes the context", method("withContext", ChatContext.class), null);

        assertEquals("set", execute(tool, null, new ChatContext()));
        assertEquals("null", execute(tool, null, null));
    }

    @Test
    void matchingValueIsPassedThrough() throws Exception {
        when(codec.decode(any(), any())).thenReturn(Map.of("message", "hello"));
        MethodTool tool = reader.of("take", "Takes a string", method("take", String.class), null);

        Object[] values = tool.resolveArguments("{\"message\":\"hello\"}", null);

        assertEquals("hello", values[0]);
    }

    @Test
    void valueNotFittingItsParameterGoesThroughTheCodec() throws Exception {
        when(codec.decode(any(), any())).thenReturn(Map.of("n", "21"));
        when(codec.convert("21", int.class)).thenReturn(21);
        MethodTool tool = reader.of("twice", "Doubles a number", method("twice", int.class), null);

        Object[] values = tool.resolveArguments("{\"n\":\"21\"}", null);

        assertEquals(21, values[0]);
    }

    @Test
    void missingValueForAPrimitiveNamesTheParameter() {
        when(codec.decode(any(), any())).thenReturn(Map.of());
        MethodTool tool = reader.of("save", "Saves an id", method("save", int.class), null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> tool.resolveArguments("{}", null));
        assertTrue(failure.getMessage().contains("'id'"));
        assertTrue(failure.getMessage().contains("save"));
    }

    @Test
    void missingValueForAnObjectIsNull() throws Exception {
        when(codec.decode(any(), any())).thenReturn(Map.of());
        MethodTool tool = reader.of("take", "Takes a string", method("take", String.class), null);

        assertNull(tool.resolveArguments("{}", null)[0]);
    }

    @Test
    void absentArgumentsMeanNone() throws Exception {
        MethodTool tool = reader.of("take", "Takes a string", method("take", String.class), null);

        // null and blank both mean the model produced none, which is not a document to decode.
        assertNull(tool.resolveArguments(null, null)[0]);
        assertNull(tool.resolveArguments("   ", null)[0]);
        verify(codec, never()).decode(any(), any());
    }

    @Test
    void keyTheMethodDoesNotDeclareIsIgnored() throws Exception {
        when(codec.decode(any(), any())).thenReturn(Map.of("message", "hello", "extra", "ignored"));
        MethodTool tool = reader.of("take", "Takes a string", method("take", String.class), null);

        assertEquals("hello", tool.resolveArguments("{}", null)[0]);
    }

    // ===== call =====

    @Test
    void failureOutOfTheMethodArrivesUnwrapped() {
        IllegalStateException unchecked = assertThrows(IllegalStateException.class,
                () -> execute(reader.of("fail", "Always fails", method("fail"), null), ""));
        assertEquals("boom", unchecked.getMessage());

        Exception checked = assertThrows(Exception.class,
                () -> execute(reader.of("failChecked", "Fails checked", method("failChecked"), null), ""));
        assertEquals("checked", checked.getMessage());

        RuntimeException bare = assertThrows(RuntimeException.class,
                () -> execute(reader.of("weird", "Throws a bare Throwable", method("weird"), null), ""));
        assertEquals("weird", bare.getCause().getMessage());
    }

    // ===== resolveResult =====

    @Test
    void returnValueBecomesTheResult() {
        MethodTool voidMethod = reader.of("save", "Saves an id", method("save", int.class), null);
        MethodTool stringMethod = reader.of("noop", "Does nothing", method("noop"), null);
        MethodTool otherMethod = reader.of("twice", "Doubles a number", method("twice", int.class), null);
        when(codec.encode(42)).thenReturn("encoded");

        List<ContentPart> parts = voidMethod.resolveResult(null, null);
        assertEquals(1, parts.size());
        assertEquals("Success", ((TextPart) parts.get(0)).getText());
        assertEquals("ok", textOf(stringMethod.resolveResult("ok", null)));
        assertEquals("encoded", textOf(otherMethod.resolveResult(42, null)));
    }

    // ===== the pairing: schemaFor and valueFor =====

    @Test
    void oneHookPairCoversSchemaAndBinding() throws Exception {
        ChatContext context = new ChatContext();
        context.getAttributes().put("user", new CurrentUser("ada"));
        BizTool tool = BizTool.of("ship", "Ships for the current user",
                method("ship", String.class, CurrentUser.class), null, codec);

        Map<String, JsonSchema> properties = tool.definition().getInputSchema().getProperties();
        assertTrue(properties.containsKey("route"));
        assertFalse(properties.containsKey("user"));

        assertEquals("ada", execute(tool, "{\"unused\":1}", context));
    }

    @Test
    void claimingWithoutProvidingFailsLoudly() {
        ClaimOnlyTool tool = ClaimOnlyTool.of("ship", "Claims but does not provide",
                method("ship", String.class, CurrentUser.class), null, codec);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tool.resolveArguments("{}", new ChatContext()));
        assertTrue(failure.getMessage().contains("valueFor"));
    }

    @Test
    void argumentsSchemaIsTheHooksToShape() {
        ToolMethodSpec spec = resolutionOf(method("take", String.class), null);
        spec.setName("shaped");
        ShapedTool tool = new ShapedTool(spec, codec);

        JsonSchema schema = tool.definition().getInputSchema();
        assertEquals(List.of("message"), schema.getRequired());
        assertTrue(schema.getProperties().containsKey("extra"));
    }

    @Test
    void parameterDescriptionFromItsAnnotationReachesThePropertySchema() {
        Tool tool = new MethodTools(codec).from(new Described()).get(0);

        JsonSchema property = tool.definition().getInputSchema().getProperties().get("message");
        assertEquals("What it means", property.getDescription());
    }

    @Test
    void emptyDescriptionBecomesNoDescription() {
        MethodTool tool = reader.of("noop", "", method("noop"), null);

        assertNull(tool.definition().getDescription());
    }

    @Test
    void arrayParameterIsDescribedAndBound() throws Exception {
        when(codec.decode(any(), any())).thenReturn(Map.of("items", List.of("a", "b")));
        when(codec.convert(List.of("a", "b"), String[].class)).thenReturn(new String[] { "a", "b" });
        MethodTool tool = reader.of("join", "Joins items", method("join", String[].class), null);

        assertTrue(tool.definition().getInputSchema().getProperties().containsKey("items"));

        Object[] values = tool.resolveArguments("{\"items\":[\"a\",\"b\"]}", null);
        assertArrayEquals(new String[] { "a", "b" }, (String[]) values[0]);
    }

    // ===== required =====

    @Test
    void aTypeTheCodecDoesNotRequireIsLeftOut() {
        when(codec.generateDecodeSchema(any())).thenReturn(new JsonSchemaBuilder().setType("object").build());
        MethodTool tool = reader.of("take", "Takes a string", method("take", String.class), null);

        // the codec's answer is the only thing asked; a value it does not list as required leaves the
        // parameter out of the envelope's own required list
        assertNull(tool.definition().getInputSchema().getRequired());
    }

    @Test
    void writtenWordOverridesTheCodec() {
        Tool tool = new MethodTools(codec).from(new Written()).get(0);

        // the codec demands every property; the word on one takes it back, the word on another keeps
        // it required
        assertEquals(List.of("plain", "forced"), tool.definition().getInputSchema().getRequired());
    }

    // ===== harness =====

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return Target.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * The resolution a tool is completed from, carrying the method's signature with every parameter
     * named after the Java parameter it stands for — what {@link MethodTools} hands over once its
     * customizers and defaults have run.
     */
    private static ToolMethodSpec resolutionOf(Method method, Object target) {
        ToolMethodSpec spec = new ToolMethodSpec(method, target,
                Arrays.stream(method.getParameters()).map(ToolParameterSpec::new).toList());
        spec.getParameters().forEach(entry -> entry.setName(entry.getParameter().getName()));
        return spec;
    }

    private String execute(MethodTool tool, String arguments) throws Exception {
        return execute(tool, arguments, null);
    }

    private String execute(MethodTool tool, String arguments, ChatContext context) throws Exception {
        return textOf(tool.execute(arguments, context));
    }

    private static String textOf(List<ContentPart> parts) {
        return ((TextPart) parts.get(0)).getText();
    }

    /** The method under test; public members are reached through reflection. */
    public static class Target {

        public static String greet(String name) {
            return "hi " + name;
        }

        public static String take(String message) {
            return message;
        }

        public static String join(String[] items) {
            return String.join(",", items);
        }

        public static int twice(int n) {
            return n * 2;
        }

        public static void save(int id) {
            // nothing to do; the answer is "Success"
        }

        public static String withContext(ChatContext context) {
            return context == null ? "null" : "set";
        }

        public static String ship(String route, CurrentUser user) {
            return user.name();
        }

        public static String noop() {
            return "ok";
        }

        public static String fail() {
            throw new IllegalStateException("boom");
        }

        public static String failChecked() throws Exception {
            throw new Exception("checked");
        }

        public static String weird() throws Throwable {
            throw new Throwable("weird");
        }

        public String instanceGreet(String name) {
            return "hello " + name;
        }

        // Taken by name through getDeclaredMethod: the tests reach it reflectively, which the
        // compiler cannot see.
        @SuppressWarnings("unused")
        private static String secret() {
            return "secret!";
        }

    }

    /** A tool method whose parameter carries its meaning in the annotation. */
    public static class Described {

        @ToolMethod(name = "take")
        public String take(@ToolParam(description = "What it means") String message) {
            return message;
        }
    }

    /** One method whose parameters' required words are settled by the annotation alone. */
    public static class Written {

        @ToolMethod(name = "written")
        public String run(String plain, @ToolParam(required = "false") String loosened,
                @ToolParam(required = "true") String forced) {
            return "ok";
        }
    }

    /** The application type a subclass keeps off the wire. */
    public record CurrentUser(String name) {
    }

    /** A tool that shapes the arguments schema itself, on top of what the built-in assembly says. */
    private static class ShapedTool extends MethodTool {

        ShapedTool(ToolMethodSpec spec, JsonCodec codec) {
            super(spec, codec);
        }

        @Override
        protected JsonSchema argumentsSchema(ToolMethodSpec spec) {
            JsonSchema base = super.argumentsSchema(spec);
            Map<String, JsonSchema> properties = new LinkedHashMap<>(base.getProperties());
            properties.put("extra", new JsonSchemaBuilder().build());
            return JsonSchemaBuilder.from(base).setProperties(properties).build();
        }
    }

    /** The extension the hooks exist for: one claim pair, schema and binding together. */
    private static class BizTool extends MethodTool {

        public BizTool(ToolMethodSpec spec, JsonCodec codec) {
            super(spec, codec);
        }

        public static BizTool of(String name, String description, Method method, Object target, JsonCodec codec) {
            ToolMethodSpec spec = resolutionOf(method, target);
            spec.setName(name);
            spec.setDescription(description);
            return new BizTool(spec, codec);
        }

        @Override
        protected JsonSchema schemaFor(Parameter parameter) {
            return parameter.getType() == CurrentUser.class ? null : super.schemaFor(parameter);
        }

        @Override
        protected Object valueFor(Parameter parameter, ChatContext context) {
            if (parameter.getType() == CurrentUser.class) {
                return context.getAttributes().get("user");
            }
            return super.valueFor(parameter, context);
        }

    }

    /** Claims a type but never learns to provide it — the default valueFor must refuse. */
    private static class ClaimOnlyTool extends MethodTool {

        public ClaimOnlyTool(ToolMethodSpec spec, JsonCodec codec) {
            super(spec, codec);
        }

        public static ClaimOnlyTool of(String name, String description, Method method, Object target,
                JsonCodec codec) {
            ToolMethodSpec spec = resolutionOf(method, target);
            spec.setName(name);
            spec.setDescription(description);
            return new ClaimOnlyTool(spec, codec);
        }

        @Override
        protected JsonSchema schemaFor(Parameter parameter) {
            return parameter.getType() == CurrentUser.class ? null : super.schemaFor(parameter);
        }

    }

}
