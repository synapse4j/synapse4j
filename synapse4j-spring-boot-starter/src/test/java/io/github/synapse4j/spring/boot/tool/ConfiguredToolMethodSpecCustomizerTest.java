package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolParameterSpec;

class ConfiguredToolMethodSpecCustomizerTest {

    @Test
    void configuredValuesReplaceAnnotations() {
        ToolsProperties tools = new ToolsProperties();
        ToolMethodProperties greet = new ToolMethodProperties();
        greet.setDescription("from the configuration");
        greet.setStrict("true");
        greet.getExtras().put("x_vendor", "yes");
        ToolParameterProperties who = new ToolParameterProperties();
        who.setDescription("who to greet");
        greet.getParameters().put("who", who);
        tools.getMethods().put("greet", greet);

        ToolMethodSpec spec = specOf();
        spec.setDescription("from the annotation");
        spec.setType("from the annotation");

        new ConfiguredToolMethodSpecCustomizer(tools).customize(spec);

        assertThat(spec.getDescription()).isEqualTo("from the configuration");
        assertThat(spec.getType()).isEqualTo("from the annotation");
        assertThat(spec.getStrict()).isEqualTo("true");
        assertThat(spec.getExtras().get("x_vendor")).isEqualTo("yes");
        assertThat(spec.getParameters().get(0).getDescription()).isEqualTo("who to greet");
    }

    @Test
    void toolFoundUnderIncomingName() {
        ToolsProperties tools = new ToolsProperties();
        ToolMethodProperties greeting = new ToolMethodProperties();
        greeting.setName("greeting");
        tools.getMethods().put("greet", greeting);

        ToolMethodSpec spec = specOf();
        new ConfiguredToolMethodSpecCustomizer(tools).customize(spec);

        assertThat(spec.getName()).isEqualTo("greeting");
    }

    @Test
    void applicationStrictFillsUnstated() {
        ToolsProperties tools = new ToolsProperties();
        tools.setStrict("true");

        ToolMethodSpec unstated = specOf();
        new ConfiguredToolMethodSpecCustomizer(tools).customize(unstated);
        assertThat(unstated.getStrict()).isEqualTo("true");

        ToolMethodSpec stated = specOf();
        stated.setStrict("false");
        new ConfiguredToolMethodSpecCustomizer(tools).customize(stated);
        assertThat(stated.getStrict()).isEqualTo("false");
    }

    private static ToolMethodSpec specOf() {
        Method method = method("greet");
        return new ToolMethodSpec(method, null, List.of(new ToolParameterSpec(method.getParameters()[0])));
    }

    private static Method method(String name) {
        try {
            return Fixture.class.getMethod(name, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** The method under test; public members are reached through reflection. */
    public static class Fixture {

        public static String greet(String who) {
            return who;
        }
    }
}
