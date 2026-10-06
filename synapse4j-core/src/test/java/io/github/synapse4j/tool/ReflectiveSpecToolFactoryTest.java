package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;

class ReflectiveSpecToolFactoryTest {

    private final JsonCodec codec = mock(JsonCodec.class);

    /** The resolution the last call handed on, so the tests can see what the factory passed. */
    private final AtomicReference<ToolMethodSpec> handed = new AtomicReference<>();

    private final ReflectiveSpecToolFactory factory = new ReflectiveSpecToolFactory(
            (spec, codec) -> {
                handed.set(spec);
                return new Default(spec, codec);
            });

    @Test
    void blankTypeUsesHandedFunction() {
        ToolMethodSpec spec = specOf("");

        assertInstanceOf(Default.class, factory.create(spec, codec));
        assertSame(spec, handed.get());
    }

    @Test
    void otherTypeBecomesNamedClass() {
        assertInstanceOf(Named.class, factory.create(specOf(Named.class.getName()), codec));
    }

    @Test
    void unknownTypeRefused() {
        SynapseException failure = assertThrows(SynapseException.class,
                () -> factory.create(specOf("no.such.Tool"), codec));

        assertTrue(failure.getMessage().contains("no.such.Tool"));
        assertTrue(failure.getMessage().contains("was not found"));
    }

    @Test
    void nonToolClassRefused() {
        SynapseException failure = assertThrows(SynapseException.class,
                () -> factory.create(specOf(NotATool.class.getName()), codec));

        assertTrue(failure.getMessage().contains("is not a"));
    }

    @Test
    void classWithoutExpectedConstructorRefused() {
        SynapseException failure = assertThrows(SynapseException.class,
                () -> factory.create(specOf(NeedsAnArgument.class.getName()), codec));

        assertTrue(failure.getMessage().contains("has no constructor taking a ToolMethodSpec"));
    }

    private ToolMethodSpec specOf(String type) {
        ToolMethodSpec spec = new ToolMethodSpec(Named.method(), null, List.of());
        spec.setName("tool");
        spec.setType(type);
        return spec;
    }

    /** A tool the factory can construct but these tests never ask anything of. */
    public static class Named implements Tool {

        static java.lang.reflect.Method method() {
            try {
                return Named.class.getMethod("greet");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        public static String greet() {
            return "hi";
        }

        public Named(ToolMethodSpec spec, JsonCodec codec) {
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

    /** What a blank type stands for. */
    public static class Default extends Named {

        public Default(ToolMethodSpec spec, JsonCodec codec) {
            super(spec, codec);
        }
    }

    /** A tool a type cannot build: its only constructor takes an argument. */
    public static class NeedsAnArgument extends Named {

        public NeedsAnArgument(String argument) {
            super(null, null);
        }
    }

    /** A class that is not a tool at all. */
    public static class NotATool {
    }
}