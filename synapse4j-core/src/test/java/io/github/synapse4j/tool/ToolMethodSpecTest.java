package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.exception.SynapseException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ToolMethodSpecTest {

    @Test
    void validateRefusesAResolutionThatNamesNothing() {
        ToolMethodSpec spec = new ToolMethodSpec(method("greet", String.class), null);

        SynapseException unnamedTool = assertThrows(SynapseException.class, spec::validate);
        assertTrue(unnamedTool.getMessage().contains("no name for the tool"));

        spec.setName("greet");
        spec.getParameters().get(0).setName("");

        SynapseException unnamedParameter = assertThrows(SynapseException.class, spec::validate);
        assertTrue(unnamedParameter.getMessage().contains("no name to declare"));
    }

    @Test
    void validateRefusesTwoParametersUnderOneName() {
        ToolMethodSpec spec = new ToolMethodSpec(method("ship", String.class, String.class), null);
        spec.setName("ship");
        ToolParameterSpec first = spec.getParameters().get(0);
        spec.getParameters().get(1).setName(first.getName());

        SynapseException failure = assertThrows(SynapseException.class, spec::validate);
        assertTrue(failure.getMessage().contains("names two parameters"));
    }

    @Test
    void theParametersAreSettledWhenTheSignatureIsRead() {
        ToolMethodSpec spec = new ToolMethodSpec(method("greet", String.class), null);

        assertThrows(UnsupportedOperationException.class, () -> spec.getParameters().clear());
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return Fixture.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** The methods under test; public members are reached through reflection. */
    public static class Fixture {

        public static String greet(String name) {
            return name;
        }

        public static String ship(String from, String to) {
            return from + to;
        }
    }
}
