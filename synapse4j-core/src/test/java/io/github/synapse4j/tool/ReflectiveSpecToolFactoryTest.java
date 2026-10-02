package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonCodec;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ReflectiveSpecToolFactoryTest {

    private final ReflectiveSpecToolFactory factory = new ReflectiveSpecToolFactory(Default::new);

    @Test
    void aBlankNameIsTheSuppliersTool() {
        assertInstanceOf(Default.class, factory.create(""));
    }

    @Test
    void anyOtherNameIsTheClassItNames() {
        assertInstanceOf(Named.class, factory.create(Named.class.getName()));
    }

    @Test
    void anUnknownNameIsRefused() {
        SynapseException failure = assertThrows(SynapseException.class, () -> factory.create("no.such.Tool"));

        assertTrue(failure.getMessage().contains("no.such.Tool"));
        assertTrue(failure.getMessage().contains("was not found"));
    }

    @Test
    void aClassThatIsNotAToolIsRefused() {
        SynapseException failure = assertThrows(SynapseException.class, () -> factory.create(NotATool.class.getName()));

        assertTrue(failure.getMessage().contains("does not implement"));
    }

    @Test
    void aClassWithoutANoArgumentConstructorIsRefused() {
        SynapseException failure = assertThrows(SynapseException.class,
                () -> factory.create(NeedsAnArgument.class.getName()));

        assertTrue(failure.getMessage().contains("no-argument constructor"));
    }

    /** A tool nothing completes in these tests. */
    public static class Named implements SpecTool {

        @Override
        public void initialize(ToolMethodSpec spec, JsonCodec codec) {
            throw new AssertionError("these tests never complete a tool");
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

    /** What a blank name stands for. */
    public static class Default extends Named {
    }

    /** A tool a name cannot build: its only constructor takes an argument. */
    public static class NeedsAnArgument extends Named {

        public NeedsAnArgument(String argument) {
        }
    }

    /** A class that is not a tool at all. */
    public static class NotATool {
    }
}
