package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;

class ChatOptionsTest {

    @Test
    void effectiveFillsGapsCallWins() {
        ChatOptions call = new ChatOptions();
        call.setTemperature(0.2);
        call.getHeaders().put("x-shared", "call");
        call.getResponseFormat().setType(ChatResponseFormat.TYPE_JSON_SCHEMA);

        ChatOptions defaults = new ChatOptions();
        defaults.setModel("gpt-4o");
        defaults.setTemperature(1.0);
        defaults.getHeaders().put("x-shared", "default");
        defaults.getHeaders().put("x-default", "1");
        defaults.getExtras().put("service_tier", "flex");
        JsonSchema schema = new JsonSchemaBuilder().setType("object").build();
        defaults.getResponseFormat().setSchema(schema);

        ChatOptions effective = call.effective(defaults);

        assertEquals("gpt-4o", effective.getModel());
        assertEquals(Double.valueOf(0.2), effective.getTemperature());
        // The bag fills by key, the call's entry winning where both name one.
        assertEquals("call", effective.getHeaders().get("x-shared"));
        assertEquals("1", effective.getHeaders().get("x-default"));
        assertEquals("flex", effective.getExtras().get("service_tier"));
        // The nested format fills the same way: the call's mode, the default's schema.
        assertEquals(ChatResponseFormat.TYPE_JSON_SCHEMA, effective.getResponseFormat().getType());
        assertSame(schema, effective.getResponseFormat().getSchema());
        // The answer is a new instance; neither side is changed.
        assertNull(call.getModel());
        assertEquals(Double.valueOf(1.0), defaults.getTemperature());
    }

    @Test
    void optionsBagsNotShared() {
        ChatOptions one = new ChatOptions();
        ChatOptions two = new ChatOptions();

        one.getHeaders().put("openai-beta", "responses=v1");
        one.getExtras().put("service_tier", "flex");

        assertTrue(two.getHeaders().isEmpty());
        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void subclassCopyKeepsOwnKind() {
        SeedOptions options = new SeedOptions();
        options.setModel("gpt-4o");
        options.setSeed("42");

        SeedOptions copy = options.copy();

        assertNotSame(options, copy);
        assertEquals("gpt-4o", copy.getModel());
        assertEquals("42", copy.getSeed());
    }

    @Test
    void subclassEffectiveCarriesOwnField() {
        SeedOptions sameKind = new SeedOptions();
        sameKind.setSeed("default-seed");
        ChatOptions plain = new ChatOptions();
        plain.setModel("gpt-4o");
        SeedOptions unset = new SeedOptions();
        SeedOptions set = new SeedOptions();
        set.setSeed("call-seed");

        ChatOptions filled = unset.effective(sameKind);
        ChatOptions kept = set.effective(plain);

        // A base of the same kind fills the subclass's own field when unset; a plain base cannot
        // supply it, and a value the call set stands.
        assertEquals("default-seed", ((SeedOptions) filled).getSeed());
        assertEquals("call-seed", ((SeedOptions) kept).getSeed());
        assertEquals("gpt-4o", kept.getModel());
    }

    /** A subclass carrying a field of its own, the way a client's options type would. */
    static class SeedOptions extends ChatOptions {

        private String seed;

        SeedOptions() {
        }

        SeedOptions(SeedOptions other) {
            super(other);
            this.seed = other.seed;
        }

        String getSeed() {
            return seed;
        }

        void setSeed(String seed) {
            this.seed = seed;
        }

        @Override
        public SeedOptions copy() {
            return new SeedOptions(this);
        }

        @Override
        public void fillFrom(ChatOptions other) {
            super.fillFrom(other);
            if (seed == null && other instanceof SeedOptions options) {
                seed = options.seed;
            }
        }
    }

}
