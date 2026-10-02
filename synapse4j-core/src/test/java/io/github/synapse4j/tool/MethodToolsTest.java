package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.AbstractJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class MethodToolsTest {

    /** Every tool the reader built, in the order it asked the factory for one. */
    private final List<CapturingTool> built = new ArrayList<>();

    /** The reader under test: its factory hands back tools that keep what they were completed from. */
    private final MethodTools reader = new MethodTools(new IdleCodec()).specToolFactory(type -> {
        CapturingTool tool = new CapturingTool(type);
        built.add(tool);
        return tool;
    });

    @Test
    void onlyWhatWasWrittenLandsOnTheSpec() {
        reader.from(new Bean());

        ToolMethodSpec written = toolOf("renamed").spec();
        assertEquals("What it does", written.getDescription());
        assertEquals("some.Tool", written.getType());
        ToolParameterSpec told = written.getParameters().get(0);
        assertEquals("arg", told.getName());
        assertEquals("What it means", told.getDescription());
        assertEquals("false", told.getRequired());
        assertEquals("some.Tool", toolOf("renamed").askedFor());

        ToolMethodSpec blank = toolOf("defaulted").spec();
        assertEquals("", blank.getDescription());
        assertEquals("", blank.getType());
        ToolParameterSpec untold = blank.getParameters().get(0);
        assertEquals("value", untold.getName());
        assertEquals("", untold.getDescription());
        assertEquals("", untold.getRequired());
        assertEquals("", toolOf("defaulted").askedFor());
    }

    @Test
    void anObjectSuppliesItsInstanceAndStaticMethodsAClassSuppliesTheStatics() {
        reader.from(new Bean());
        assertEquals(Set.of("defaulted", "renamed", "fixed"), resolvedNames());

        built.clear();
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

        built.clear();
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

    private Set<String> resolvedNames() {
        return built.stream().map(tool -> tool.spec().getName()).collect(Collectors.toSet());
    }

    private CapturingTool toolOf(String name) {
        return built.stream()
                .filter(tool -> tool.spec().getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tool resolved under '" + name + "': " + resolvedNames()));
    }

    /** A method that wrote nothing, a method that wrote everything, and a static one. */
    public static class Bean {

        @ToolMethod
        public String defaulted(String value) {
            return value;
        }

        @ToolMethod(name = "renamed", description = "What it does", type = "some.Tool")
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

    /** The tool the reader hands back, keeping the name it was asked for and the resolution it got. */
    private static class CapturingTool implements SpecTool {

        private final String askedFor;

        private @Nullable ToolMethodSpec spec;

        CapturingTool(String askedFor) {
            this.askedFor = askedFor;
        }

        String askedFor() {
            return askedFor;
        }

        ToolMethodSpec spec() {
            return Objects.requireNonNull(spec, "this tool was never initialized");
        }

        @Override
        public void initialize(ToolMethodSpec spec, JsonCodec codec) {
            this.spec = spec;
        }

        @Override
        public ToolDefinition definition() {
            throw new AssertionError("these tests never ask a tool for its declaration");
        }

        @Override
        public List<ContentPart> execute(@Nullable String arguments, @Nullable ChatContext context) {
            throw new AssertionError("these tests never call a tool");
        }
    }

    /** A codec nothing reaches: a capturing tool never asks one anything. */
    private static class IdleCodec extends AbstractJsonCodec {

        @Override
        public JsonSchema generateEncodeSchema(Type type) {
            throw new AssertionError("nothing encodes here");
        }

        @Override
        public JsonSchema generateDecodeSchema(Type type) {
            throw new AssertionError("nothing decodes here");
        }

        @Override
        protected String encodeValue(Object value) {
            throw new AssertionError("nothing encodes here");
        }

        @Override
        protected <T> T decodeValue(String json, Type type) {
            throw new AssertionError("nothing decodes here");
        }

        @Override
        public JsonWriter writer(OutputStream out) {
            throw new AssertionError("nothing writes here");
        }

        @Override
        public JsonReader reader(InputStream in) {
            throw new AssertionError("nothing reads here");
        }
    }
}
