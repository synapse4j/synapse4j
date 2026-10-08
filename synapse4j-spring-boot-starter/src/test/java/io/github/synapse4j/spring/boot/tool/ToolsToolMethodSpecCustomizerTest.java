package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolParameterSpec;

class ToolsToolMethodSpecCustomizerTest {

    @Test
    void prefixPrependedToToolNames() {
        ToolMethodSpec named = specOf(Prefixed.class);
        named.setName("daily");
        new ToolsToolMethodSpecCustomizer().customize(named);
        assertThat(named.getName()).isEqualTo("weather-daily");

        ToolMethodSpec unnamed = specOf(Prefixed.class);
        new ToolsToolMethodSpecCustomizer().customize(unnamed);
        assertThat(unnamed.getName()).isEqualTo("weather-forecast");
    }

    @Test
    void blankPrefixUnmarkedLeftAlone() {
        ToolMethodSpec unprefixed = specOf(Unprefixed.class);
        new ToolsToolMethodSpecCustomizer().customize(unprefixed);
        assertThat(unprefixed.getName()).isEmpty();

        ToolMethodSpec unmarked = specOf(Unmarked.class);
        new ToolsToolMethodSpecCustomizer().customize(unmarked);
        assertThat(unmarked.getName()).isEmpty();
    }

    @Test
    void inheritedMethodKeepsClassPrefix() {
        // The method is the superclass's, the tool the marked subclass's: the prefix is read off the
        // class the reader was asked about, not the one that declares the method.
        Method inherited = method(Inheriting.class);
        ToolMethodSpec spec = new ToolMethodSpec(Inheriting.class, inherited, null,
                List.of(new ToolParameterSpec(inherited.getParameters()[0])));

        new ToolsToolMethodSpecCustomizer().customize(spec);

        assertThat(spec.getName()).isEqualTo("child-forecast");
    }

    private static ToolMethodSpec specOf(Class<?> type) {
        Method method = method(type);
        return new ToolMethodSpec(method, null, List.of(new ToolParameterSpec(method.getParameters()[0])));
    }

    private static Method method(Class<?> type) {
        try {
            return type.getMethod("forecast", String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** A marked class whose prefix, separator and all, is written through the alias. */
    @Tools("weather-")
    public static class Prefixed {

        public static String forecast(String city) {
            return city;
        }
    }

    /** A marked class that writes no prefix. */
    @Tools
    public static class Unprefixed {

        public static String forecast(String city) {
            return city;
        }
    }

    /** A class no annotation marks. */
    public static class Unmarked {

        public static String forecast(String city) {
            return city;
        }
    }

    /** A marked class whose tool method is one its unmarked superclass declares. */
    @Tools("child-")
    public static class Inheriting extends Declaring {
    }

    /** The unmarked class that declares the method the subclass only inherits. */
    public static class Declaring {

        public static String forecast(String city) {
            return city;
        }
    }
}
