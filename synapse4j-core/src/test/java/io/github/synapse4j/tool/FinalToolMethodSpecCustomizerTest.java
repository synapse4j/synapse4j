package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalToolMethodSpecCustomizerTest {

    private final JsonCodec codec = mock(JsonCodec.class);

    private final FinalToolMethodSpecCustomizer customizer = new FinalToolMethodSpecCustomizer(codec);

    /** What the codec answers for any schema question; what it is does not matter to these tests. */
    private final JsonSchema answered = new JsonSchemaBuilder().setType("object").setRequired(List.of("value")).build();

    @BeforeEach
    void codecAnswersOneSchema() {
        when(codec.generateDecodeSchema(any())).thenReturn(answered);
    }

    @Test
    void whatNobodyWroteIsSettledFromTheMethod() {
        ToolMethodSpec spec = specOf();

        customizer.customize(spec);

        assertEquals("greet", spec.getName());

        ToolParameterSpec name = spec.getParameters().get(0);
        assertEquals("name", name.getName());
        assertEquals("true", name.getFromModel());
        assertEquals("true", name.getRequired());
        assertSame(answered, name.getResolvedSchema());

        ToolParameterSpec context = spec.getParameters().get(2);
        assertEquals("context", context.getName());
        assertEquals("false", context.getFromModel());
        assertEquals("", context.getRequired());
        assertNull(context.getResolvedSchema());

        assertTrue(spec.getResolvedSchema().getProperties().containsKey("name"));
        assertTrue(spec.getResolvedSchema().getProperties().containsKey("greeting"));
        assertNull(spec.getResolvedSchema().getProperties().get("context"));
        assertEquals(List.of("name", "greeting"), spec.getResolvedSchema().getRequired());
    }

    @Test
    void whatACustomizerWroteIsLeftAlone() {
        ToolMethodSpec spec = specOf();
        spec.setName("chosen");
        JsonSchema wholeSchema = new JsonSchemaBuilder().setType("integer").build();
        spec.setResolvedSchema(wholeSchema);

        ToolParameterSpec name = spec.getParameters().get(0);
        name.setName("who");
        name.setDescription("Whom to greet");
        name.setFromModel("false");
        name.setRequired("false");
        JsonSchema resolved = new JsonSchemaBuilder().setType("boolean").build();
        name.setResolvedSchema(resolved);

        ToolParameterSpec greeting = spec.getParameters().get(1);
        greeting.setSchema("{\"type\":\"number\"}");
        JsonSchema read = new JsonSchemaBuilder().setType("number").build();
        when(codec.decode(anyString(), eq(JsonSchema.class))).thenReturn(read);

        customizer.customize(spec);

        assertEquals("chosen", spec.getName());
        assertSame(wholeSchema, spec.getResolvedSchema());

        assertEquals("who", name.getName());
        assertEquals("false", name.getFromModel());
        assertEquals("false", name.getRequired());
        assertSame(resolved, name.getResolvedSchema());

        assertSame(read, greeting.getResolvedSchema());
    }

    private static ToolMethodSpec specOf() {
        Method method = method("greet");
        List<ToolParameterSpec> parameters = Arrays.stream(method.getParameters())
                .map(ToolParameterSpec::new).toList();
        return new ToolMethodSpec(method, null, parameters);
    }

    private static Method method(String name) {
        try {
            return Fixture.class.getMethod(name, String.class, String.class, ChatContext.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** The method under test; public members are reached through reflection. */
    public static class Fixture {

        public static String greet(String name, String greeting, ChatContext context) {
            return greeting + " " + name;
        }
    }
}