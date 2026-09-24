package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.json.AbstractJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MethodToolTest {

    private final FakeCodec codec = new FakeCodec();

    // ===== factories and construction =====

    @Test
    void factoryWithSignatureRefusesMissingParts() {
        Method greet = method("greet", String.class);

        assertThrows(NullPointerException.class, () -> MethodTool.of(null, "d", greet, null, codec));
        assertThrows(NullPointerException.class, () -> MethodTool.of("n", null, greet, null, codec));
        assertThrows(NullPointerException.class, () -> MethodTool.of("n", "d", null, null, codec));
        assertThrows(NullPointerException.class, () -> MethodTool.of("n", "d", greet, null, null));
    }

    @Test
    void factoryWithDeclarationRefusesMissingParts() {
        Method greet = method("greet", String.class);
        ToolDefinition nameless = new ToolDefinition(null, "d", "encoded");

        assertThrows(NullPointerException.class, () -> MethodTool.of(null, greet, null, codec));
        assertThrows(NullPointerException.class, () -> MethodTool.of(nameless, greet, null, codec));
        assertThrows(NullPointerException.class,
                () -> MethodTool.of(new ToolDefinition("n", "d", "s"), greet, null, null));
    }

    @Test
    void instanceMethodDemandsATargetStaticMethodDoesNot() {
        Method instance = method("instanceGreet", String.class);
        Method statik = method("greet", String.class);

        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> MethodTool.of("n", "d", instance, null, codec));
        assertTrue(refused.getMessage().contains("instance method"));

        MethodTool.of("n", "d", statik, null, codec);
        MethodTool.of("n", "d", statik, new Object(), codec);
    }

    @Test
    void signatureBuildsTheDeclaration() {
        MethodTool tool = MethodTool.of("weather", "Looks up weather", method("take", String.class), null, codec);
        ToolDefinition definition = tool.definition();

        assertEquals("weather", definition.getName());
        assertEquals("Looks up weather", definition.getDescription());
        assertEquals("encoded", definition.getInputSchema());
        assertInstanceOf(Map.class, lastEncoded());
        assertTrue(lastEncoded().containsKey("properties"));
        assertEquals(List.of("message"), lastEncoded().get("required"));
        assertTrue(codec.generatedFor.contains(String.class));
    }

    @Test
    void chatContextParameterStaysOutOfTheSchema() {
        MethodTool.of("ctx", "Takes the context", method("withContext", ChatContext.class), null, codec);

        // toMap omits empty collections, so an envelope with nothing in it carries neither keyword
        assertFalse(lastEncoded().containsKey("properties"));
        assertFalse(lastEncoded().containsKey("required"));
        assertTrue(codec.generatedFor.isEmpty());
    }

    @Test
    void handedDeclarationIsKeptAsIs() {
        Method definition = method("withContext", ChatContext.class);
        ToolDefinition handed = new ToolDefinition("handed", "Built by hand", "{\"type\":\"object\"}");

        MethodTool tool = MethodTool.of(handed, definition, null, codec);

        assertSame(handed, tool.definition());
        assertTrue(codec.encoded.isEmpty());
    }

    @Test
    void privateMethodsRunOnceHandedOver() throws Exception {
        MethodTool tool = MethodTool.of("secret", "A private method", Target.class.getDeclaredMethod("secret"), null,
                codec);

        assertEquals("secret!", execute(tool, ""));
    }

    @Test
    void definitionBeforeDefineIsLoud() {
        MethodTool undefined = new MethodTool(method("greet", String.class), null, codec);

        IllegalStateException failure = assertThrows(IllegalStateException.class, undefined::definition);
        assertTrue(failure.getMessage().contains("define"));
    }

    // ===== resolveArguments =====

    @Test
    void chatContextParameterReceivesTheConversation() throws Exception {
        MethodTool tool = MethodTool.of("ctx", "Takes the context", method("withContext", ChatContext.class), null,
                codec);
        ChatContext context = new ChatContext();

        assertEquals("set", execute(tool, null, context));
    }

    @Test
    void chatContextParameterReceivesNullWhenNoneWasAttached() throws Exception {
        MethodTool tool = MethodTool.of("ctx", "Takes the context", method("withContext", ChatContext.class), null,
                codec);

        assertEquals("null", execute(tool, null, null));
    }

    @Test
    void matchingTypeTakesTheValueWithoutTheCodec() throws Exception {
        codec.arguments = Map.of("message", "hello");
        MethodTool tool = MethodTool.of("take", "Takes a string", method("take", String.class), null, codec);
        int encodingsBefore = codec.encoded.size();

        Object[] values = tool.resolveArguments("{\"message\":\"hello\"}", null);

        assertEquals("hello", values[0]);
        assertEquals(encodingsBefore, codec.encoded.size());
    }

    @Test
    void mismatchedTypeFallsBackThroughTheCodec() throws Exception {
        codec.arguments = Map.of("n", 21);
        codec.decodedByType.put(int.class, 21);
        MethodTool tool = MethodTool.of("twice", "Doubles a number", method("twice", int.class), null, codec);

        Object[] values = tool.resolveArguments("{\"n\":21}", null);

        assertEquals(21, values[0]);
        assertTrue(codec.encoded.contains(21));
        assertTrue(codec.decodedFor.contains(int.class));
    }

    @Test
    void missingKeyForAPrimitiveNamesTheParameter() {
        codec.arguments = Map.of();
        MethodTool tool = MethodTool.of("save", "Saves an id", method("save", int.class), null, codec);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> tool.resolveArguments("{}", null));
        assertTrue(failure.getMessage().contains("'id'"));
        assertTrue(failure.getMessage().contains("save"));
    }

    @Test
    void missingKeyForAnObjectIsNull() throws Exception {
        codec.arguments = Map.of();
        MethodTool tool = MethodTool.of("take", "Takes a string", method("take", String.class), null, codec);

        assertNull(tool.resolveArguments("{}", null)[0]);
    }

    @Test
    void keysTheMethodDoesNotDeclareAreIgnored() throws Exception {
        codec.arguments = Map.of("message", "hello", "extra", "ignored");
        MethodTool tool = MethodTool.of("take", "Takes a string", method("take", String.class), null, codec);

        assertEquals("hello", tool.resolveArguments("{}", null)[0]);
    }

    @Test
    void blankArgumentsMeanNoTextAtAll() throws Exception {
        MethodTool envOnly = MethodTool.of("ctx", "Takes the context", method("withContext", ChatContext.class), null,
                codec);
        MethodTool zero = MethodTool.of("noop", "Does nothing", method("noop"), null, codec);

        assertEquals("null", execute(envOnly, null, null));
        assertEquals("ok", execute(zero, null));
        assertTrue(codec.decodedFor.isEmpty());
    }

    // ===== call =====

    @Test
    void failureOutOfTheMethodArrivesAsItself() {
        MethodTool tool = MethodTool.of("fail", "Always fails", method("fail"), null, codec);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> execute(tool, ""));
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void checkedFailureOutOfTheMethodArrivesAsItself() {
        MethodTool tool = MethodTool.of("failChecked", "Fails checked", method("failChecked"), null, codec);

        Exception thrown = assertThrows(Exception.class, () -> execute(tool, ""));
        assertEquals("checked", thrown.getMessage());
    }

    @Test
    void aCauseThatIsNeitherExceptionNorErrorIsWrapped() {
        MethodTool tool = MethodTool.of("weird", "Throws a bare Throwable", method("weird"), null, codec);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> execute(tool, ""));
        assertEquals("weird", thrown.getCause().getMessage());
    }

    // ===== resolveResult =====

    @Test
    void voidAnswersSuccess() throws Exception {
        MethodTool tool = MethodTool.of("save", "Saves an id", method("save", int.class), null, codec);
        codec.arguments = Map.of("id", 7);
        codec.decodedByType.put(int.class, 7);

        assertEquals("Success", execute(tool, "{\"id\":7}"));
    }

    @Test
    void stringAnswersAsItself() throws Exception {
        MethodTool tool = MethodTool.of("greet", "Greets", method("greet", String.class), null, codec);
        codec.arguments = Map.of("name", "Ada");

        assertEquals("hi Ada", execute(tool, "{\"name\":\"Ada\"}"));
    }

    @Test
    void otherValuesAreRenderedByTheCodec() throws Exception {
        MethodTool tool = MethodTool.of("twice", "Doubles a number", method("twice", int.class), null, codec);
        codec.arguments = Map.of("n", 21);
        codec.decodedByType.put(int.class, 21);

        assertEquals("encoded", execute(tool, "{\"n\":21}"));
        assertTrue(codec.encoded.contains(42));
    }

    // ===== the pairing: schemaFor and valueFor =====

    @Test
    void oneHookPairCoversSchemaAndBinding() throws Exception {
        ChatContext context = new ChatContext();
        context.getAttributes().put("user", new CurrentUser("ada"));
        BizTool tool = BizTool.of("ship", "Ships for the current user", method("ship", String.class, CurrentUser.class),
                null, codec);

        // schema side: the claimed parameter is not declared to the model
        Map<?, ?> properties = (Map<?, ?>) lastEncoded().get("properties");
        assertTrue(properties.containsKey("route"));
        assertFalse(properties.containsKey("user"));

        // binding side: the claimed parameter comes from the context — one override pair, both ends
        assertEquals("ada", tool.execute("{\"unused\":1}", context));
    }

    @Test
    void claimingWithoutProvidingFailsLoudly() {
        ClaimOnlyTool tool = ClaimOnlyTool.of("ship", "Claims but does not provide",
                method("ship", String.class, CurrentUser.class), null, codec);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> tool.resolveArguments("{}", new ChatContext()));
        assertTrue(failure.getMessage().contains("valueFor"));
    }

    // ===== decoration path =====

    @Test
    void declarationCanBeDecoratedBetweenTheTwoFactories() {
        codec.schemaDocument = Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string")));
        MethodTool generated = MethodTool.of("take", "Takes a string", method("take", String.class), null, codec);

        JsonSchema schema = codec.decode(generated.definition().getInputSchema(), JsonSchema.class);
        schema.getProperties().get("message").setDescription("what to take");
        String rendered = codec.encode(schema);
        // the codec answers "encoded" with whatever document was last written — keep them in step
        codec.schemaDocument = lastEncoded();
        ToolDefinition decorated = new ToolDefinition("take", "New description", rendered);

        MethodTool rebuilt = MethodTool.of(decorated, method("take", String.class), null, codec);

        assertSame(decorated, rebuilt.definition());
        JsonSchema rebuiltSchema = codec.decode(decorated.getInputSchema(), JsonSchema.class);
        assertEquals("what to take", rebuiltSchema.getProperties().get("message").getDescription());
    }

    // ===== harness =====

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return Target.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private String execute(MethodTool tool, String arguments) throws Exception {
        return execute(tool, arguments, null);
    }

    private String execute(MethodTool tool, String arguments, ChatContext context) throws Exception {
        return tool.execute(arguments, context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastEncoded() {
        return (Map<String, Object>) codec.encoded.get(codec.encoded.size() - 1);
    }

    /** The method under test; public members are reached through reflection. */
    public static class Target {

        public static String greet(String name) {
            return "hi " + name;
        }

        public static String take(String message) {
            return message;
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

    /** The application type a subclass keeps off the wire. */
    public record CurrentUser(String name) {
    }

    /** The extension the hooks exist for: one claim pair, schema and binding together. */
    private static class BizTool extends MethodTool {

        private BizTool(Method method, Object target, JsonCodec codec) {
            super(method, target, codec);
        }

        public static BizTool of(String name, String description, Method method, Object target, JsonCodec codec) {
            BizTool tool = new BizTool(method, target, codec);
            tool.define(name, description);
            return tool;
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

        private ClaimOnlyTool(Method method, Object target, JsonCodec codec) {
            super(method, target, codec);
        }

        public static ClaimOnlyTool of(String name, String description, Method method, Object target, JsonCodec codec) {
            ClaimOnlyTool tool = new ClaimOnlyTool(method, target, codec);
            tool.define(name, description);
            return tool;
        }

        @Override
        protected JsonSchema schemaFor(Parameter parameter) {
            return parameter.getType() == CurrentUser.class ? null : super.schemaFor(parameter);
        }

    }

    /**
     * A codec that moves what a test sets up: the arguments map for the model's text, a value
     * per type for the binding fallback, and a schema document for the decoration path.
     */
    private static class FakeCodec extends AbstractJsonCodec {

        /** What {@code decode(argumentsText, Map.class)} answers; the text itself is ignored. */
        private Map<String, Object> arguments;

        /** What {@code decode("SCHEMA", ...)} answers — the decoration path reads a document. */
        private Map<String, Object> schemaDocument;

        /** What the binding fallback decodes, keyed by target type. */
        private final Map<Type, Object> decodedByType = new java.util.HashMap<>();

        /** Everything {@code encode} was asked to render, in order. */
        private final List<Object> encoded = new ArrayList<>();

        /** The types {@code generateDecodeSchema} was asked for, in order. */
        private final List<Type> generatedFor = new ArrayList<>();

        /** The types the binding fallback decoded, in order. */
        private final List<Type> decodedFor = new ArrayList<>();

        @Override
        public JsonSchema generateEncodeSchema(Type type) {
            return new JsonSchema();
        }

        @Override
        public JsonSchema generateDecodeSchema(Type type) {
            generatedFor.add(type);
            JsonSchema schema = new JsonSchema();
            schema.setType("object");
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
            if ("SCHEMA".equals(json) || (type == Map.class && "encoded".equals(json))) {
                return (T) schemaDocument;
            }
            if (type == Map.class) {
                return (T) arguments;
            }
            decodedFor.add(type);
            if (decodedByType.containsKey(type)) {
                return (T) decodedByType.get(type);
            }
            throw new AssertionError("unexpected decode of type " + type);
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
