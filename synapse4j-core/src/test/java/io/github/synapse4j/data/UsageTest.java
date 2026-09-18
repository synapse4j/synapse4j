package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class UsageTest {

    @Test
    void newUsageHasNoCounts() {
        Usage usage = new Usage();

        assertNull(usage.getInputTokens());
        assertNull(usage.getOutputTokens());
        assertNull(usage.getCachedInputTokens());
        assertTrue(usage.getExtras().isEmpty());
    }

    @Test
    void settersCarryEveryCount() {
        Usage usage = new Usage();

        usage.setInputTokens(2065);
        usage.setOutputTokens(76);
        usage.setCachedInputTokens(2048);

        assertEquals(2065, usage.getInputTokens());
        assertEquals(76, usage.getOutputTokens());
        assertEquals(2048, usage.getCachedInputTokens());
    }

    @Test
    void extrasCarryCountsTheModelDoesNotKnow() {
        Usage usage = new Usage();

        usage.getExtras().put("cache_creation_input_tokens", 2008);
        usage.getExtras().put(List.of("billed_units", "input_tokens"), 100);

        assertEquals(2008, usage.getExtras().get("cache_creation_input_tokens"));
        assertEquals(100, usage.getExtras().get("billed_units", "input_tokens"));
    }

    @Test
    void everyUsageGetsItsOwnBag() {
        Usage one = new Usage();
        Usage two = new Usage();

        one.getExtras().put("cache_creation_input_tokens", 2008);

        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void equalityAndHashCodeCoverEveryField() {
        Usage one = new Usage();
        Usage two = new Usage();

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.setInputTokens(1);
        assertNotEquals(one, two);
        two.setInputTokens(null);

        two.setOutputTokens(2);
        assertNotEquals(one, two);
        two.setOutputTokens(null);

        two.setCachedInputTokens(3);
        assertNotEquals(one, two);
        two.setCachedInputTokens(null);

        two.getExtras().put("cache_creation_input_tokens", 2008);
        assertNotEquals(one, two);
    }

    @Test
    void toStringMentionsTheCounts() {
        Usage usage = new Usage();
        usage.setInputTokens(2065);

        String rendered = usage.toString();

        assertTrue(rendered.contains("inputTokens=2065"));
        assertTrue(rendered.contains("extras="));
    }

}
