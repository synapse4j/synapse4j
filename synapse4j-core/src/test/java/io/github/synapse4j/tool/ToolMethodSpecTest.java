package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonSchemaBuilder;

class ToolMethodSpecTest {

    @Test
    void resolutionRefusedWhenNotDeclarable() {
        ToolMethodSpec spec = specOf();

        assertThrows(SynapseException.class, spec::definition);

        spec.setName("greet");

        assertThrows(SynapseException.class, spec::definition);

        spec.getParameters().get(1).setName("to");
        spec.getParameters().get(0).setName("to");

        assertThrows(SynapseException.class, spec::definition);

        spec.getParameters().get(0).setName("from");

        assertThrows(SynapseException.class, spec::definition);

        spec.setResolvedSchema(new JsonSchemaBuilder().setType("object").build());
        spec.getParameters().get(0).setFromModel("false");

        assertThrows(SynapseException.class, spec::definition);
    }

    @Test
    void strictLeftToProtocol() {
        ToolMethodSpec spec = declarable();

        assertNull(spec.definition().getStrict());

        spec.setStrict("true");
        assertEquals(Boolean.TRUE, spec.definition().getStrict());

        spec.setStrict("false");
        assertEquals(Boolean.FALSE, spec.definition().getStrict());
    }

    @Test
    void extrasCustomizerWroteReachDeclaration() {
        ToolMethodSpec spec = declarable();
        spec.setExtras(new ProviderExtras().put("x_vendor", "yes"));

        assertEquals("yes", spec.definition().getExtras().get("x_vendor"));
    }

    private static ToolMethodSpec specOf() {
        Method method = method("greet");
        return new ToolMethodSpec(method, null,
                Arrays.stream(method.getParameters()).map(ToolParameterSpec::new).toList());
    }

    /** A resolution that can become a declaration: named throughout, with an input schema settled. */
    private static ToolMethodSpec declarable() {
        ToolMethodSpec spec = specOf();
        spec.setName("greet");
        spec.setResolvedSchema(new JsonSchemaBuilder().setType("object").build());
        spec.getParameters().forEach(entry -> entry.setName(entry.getParameter().getName()));
        return spec;
    }

    private static Method method(String name) {
        try {
            return Fixture.class.getMethod(name, String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** The method under test; public members are reached through reflection. */
    public static class Fixture {

        public static String greet(String name, String greeting) {
            return greeting + " " + name;
        }
    }
}