package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchemaBuilder;

class MethodToolsTest {

    /** Every resolution the reader handed to the factory, in the order it asked for one. */
    private final List<ToolMethodSpec> built = new ArrayList<>();

    /** The tool each of those resolutions produced, in the same order. */
    private final List<Tool> tools = new ArrayList<>();

    /** The codec the reader settles schemas with; what it answers does not matter to these tests. */
    private final JsonCodec codec = mock(JsonCodec.class);

    /** The reader under test: its factory records what it was handed and hands back a mock. */
    private final MethodTools reader = new MethodTools(codec).specToolFactory((spec, json) -> {
        built.add(spec);
        Tool tool = mock(Tool.class);
        tools.add(tool);
        return tool;
    });

    @BeforeEach
    void codecAnswersASchema() {
        when(codec.generateDecodeSchema(any())).thenReturn(new JsonSchemaBuilder().setType("string").build());
    }

    @Test
    void whatTheAnnotationsWroteLandsOnTheSpec() {
        reader.from(new Bean());

        ToolMethodSpec written = specOf("renamed");
        assertEquals("What it does", written.getDescription());
        assertEquals("some.Tool", written.getType());
        assertEquals("true", written.getStrict());
        ToolParameterSpec told = written.getParameters().get(0);
        assertEquals("arg", told.getName());
        assertEquals("What it means", told.getDescription());
        assertEquals("false", told.getRequired());
        assertEquals("some.Tool", specOf("renamed").getType());
    }

    @Test
    void blanksReachTheCustomizersAndTheDefaultsFillThem() {
        List<String> names = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        reader.addCustomizer(spec -> {
            if (spec.getMethod().getName().equals("defaulted")) {
                names.add(spec.getName());
                parameters.add(spec.getParameters().get(0).getName());
            }
        });

        reader.from(new Bean());

        assertEquals(List.of(""), names);
        assertEquals(List.of(""), parameters);

        ToolMethodSpec built = specOf("defaulted");
        assertEquals("defaulted", built.getName());
        assertEquals("value", built.getParameters().get(0).getName());
        assertEquals("", built.getDescription());
        assertEquals("", built.getType());
    }

    @Test
    void anObjectSuppliesItsInstanceAndStaticMethodsAClassSuppliesTheStatics() {
        reader.from(new Bean());
        assertEquals(Set.of("defaulted", "renamed", "fixed"), resolvedNames());

        reset();
        reader.from(Bean.class);
        assertEquals(Set.of("fixed"), resolvedNames());
    }

    @Test
    void customizersRunInOrder() {
        StringBuilder order = new StringBuilder();
        reader.addCustomizer(spec -> order.append("first")).addCustomizer(spec -> order.append("second"));

        reader.from(new One());

        assertEquals("firstsecond", order.toString());
    }

    @Test
    void twoMethodsUnderOneNameAreRefused() {
        SynapseException failure = assertThrows(SynapseException.class, () -> reader.from(new Doubled()));

        assertTrue(failure.getMessage().contains("'same'"));
        assertTrue(failure.getMessage().contains("first"));
        assertTrue(failure.getMessage().contains("second"));
    }

    @Test
    void methodsOfAnyVisibilityAreRead() {
        reader.from(new Child());
        assertEquals(Set.of("own", "inherited"), resolvedNames());

        reset();
        reader.from(new Hidden());
        assertEquals(Set.of("hidden"), resolvedNames());
    }

    @Test
    void anOverrideWithoutTheAnnotationTakesTheToolAway() {
        reader.from(new Overriding());

        assertEquals(Set.of(), resolvedNames());
    }

    @Test
    void aDefaultMethodIsReadFromItsInterface() {
        reader.from(new Implementing());

        assertEquals(Set.of("shared"), resolvedNames());
    }

    @Test
    void aBridgeMethodIsNotReadAsASecondTool() {
        reader.from(new Narrowed());

        assertEquals(Set.of("echo"), resolvedNames());
    }

    @Test
    void aStaticMethodCarriesNoTargetEvenWhenReadFromAnObject() {
        Object instance = new Bean();
        reader.from(instance);

        assertSame(instance, specOf("defaulted").getTarget());
        assertNull(specOf("fixed").getTarget());
    }

    @Test
    void aClassWithoutAnnotatedMethodsYieldsNoTools() {
        assertTrue(reader.from(Plain.class).isEmpty());
        assertTrue(reader.from(new Plain()).isEmpty());
    }

    private void reset() {
        built.clear();
        tools.clear();
    }

    private Set<String> resolvedNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolMethodSpec spec : built) {
            names.add(spec.getName());
        }
        return names;
    }

    private ToolMethodSpec specOf(String name) {
        for (ToolMethodSpec spec : built) {
            if (name.equals(spec.getName())) {
                return spec;
            }
        }
        throw new AssertionError("no tool resolved under '" + name + "': " + resolvedNames());
    }

    /** A method that wrote nothing, a method that wrote everything, and a static one. */
    public static class Bean {

        @ToolMethod
        public String defaulted(String value) {
            return value;
        }

        @ToolMethod(name = "renamed", description = "What it does", type = "some.Tool", strict = "true")
        public String written(
                @ToolParam(name = "arg", description = "What it means", required = "false") String value) {
            return value;
        }

        @ToolMethod(name = "fixed")
        public static String statik() {
            return "";
        }
    }

    /** Two methods that would be known by one name. */
    public static class Doubled {

        @ToolMethod(name = "same")
        public String first() {
            return "";
        }

        @ToolMethod(name = "same")
        public String second() {
            return "";
        }
    }

    /** One annotated method, so a test can count the customizer runs exactly. */
    public static class One {

        @ToolMethod
        public String only() {
            return "";
        }
    }

    /** The tool method a subclass only inherits, and cannot reach as a public one. */
    public static class Base {

        @ToolMethod(name = "inherited")
        protected String shared() {
            return "";
        }
    }

    public static class Child extends Base {

        @ToolMethod(name = "own")
        public String own() {
            return "";
        }
    }

    /** A tool method no one outside the class sees. */
    public static class Hidden {

        @ToolMethod(name = "hidden")
        private String hidden() {
            return "";
        }
    }

    /** A tool method a subclass overrides without repeating the annotation. */
    public static class Rooted {

        @ToolMethod(name = "kept")
        public String kept() {
            return "";
        }
    }

    public static class Overriding extends Rooted {

        @Override
        public String kept() {
            return "";
        }
    }

    /** A tool method nothing but an interface declares. */
    public interface Shared {

        @ToolMethod(name = "shared")
        default String shared(String value) {
            return value;
        }
    }

    public static class Implementing implements Shared {
    }

    /** A generic superclass whose method a subclass narrows to one type. */
    public static class GenericBase<T> {

        public String echo(T value) {
            return String.valueOf(value);
        }
    }

    /** The narrowing override: the compiler adds a bridge that carries the copied annotation. */
    public static class Narrowed extends GenericBase<String> {

        @Override
        @ToolMethod(name = "echo")
        public String echo(String value) {
            return value;
        }
    }

    /** Methods, none of them a tool. */
    public static class Plain {

        public String plain(String value) {
            return value;
        }

        public static String plainStatic() {
            return "";
        }
    }

}
