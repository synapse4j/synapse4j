package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonViewTest {

    private final Map<String, Object> decoded = Map.of(
            "model", "gpt-test",
            "n", 7,
            "ratio", 0.5,
            "stream", true,
            "usage", Map.of("prompt_tokens", 11, "cached_tokens", 0),
            "choices", List.of(
                    Map.of("finish_reason", "stop", "message", Map.of("content", "Hi")),
                    Map.of("finish_reason", "length")));

    private final JsonView root = JsonView.of(decoded);

    @Test
    void readsNestedValuesWithoutTypeChecks() {
        assertEquals("Hi", root.get("choices").get(0).get("message").get("content").asText());
        assertEquals(11L, root.get("usage").get("prompt_tokens").asLong());
        assertEquals("length", root.get("choices").get(1).get("finish_reason").asText());
    }

    @Test
    void failedNavigationYieldsTheMissingSentinel() {
        JsonView missing = root.get("nope");

        assertTrue(missing.isMissing());
        assertFalse(missing.isNull());
        assertNull(missing.asText());
        // navigation on the sentinel keeps yielding the sentinel, so chains never break
        assertTrue(missing.get("deeper").get(3).isMissing());
        assertTrue(root.get("usage").get("nope").isMissing());
        assertTrue(root.get("choices").get(9).isMissing());
    }

    @Test
    void distinguishesJsonNullFromMissing() {
        Map<String, Object> withNull = new java.util.LinkedHashMap<>(decoded);
        withNull.put("nothing", null);

        JsonView view = JsonView.of(withNull);

        assertTrue(view.get("nothing").isNull());
        assertFalse(view.get("nothing").isMissing());
        assertTrue(view.get("absent").isMissing());
        assertFalse(view.get("absent").isNull());
    }

    @Test
    void typePredicatesAndCoercionsAreNullSafe() {
        assertTrue(root.get("model").isText());
        assertTrue(root.get("n").isNumber());
        assertTrue(root.get("stream").isBoolean());
        assertTrue(root.get("usage").isObject());
        assertTrue(root.get("choices").isArray());

        // numbers and booleans have a plain-text spelling; mismatched kinds yield null
        assertEquals("7", root.get("n").asText());
        assertEquals("true", root.get("stream").asText());
        assertNull(root.get("model").asLong());
        assertNull(root.get("model").asBoolean());
        assertNull(root.get("choices").asText());
        assertNull(root.get("n").asBoolean());
        assertEquals(0.5, root.get("ratio").asDouble());
    }

    @Test
    void reportsSizeForContainersAndZeroOtherwise() {
        assertEquals(6, root.size());
        assertEquals(2, root.get("choices").size());
        assertEquals(2, root.get("usage").size());
        assertEquals(0, root.get("model").size());
        assertEquals(0, root.get("nope").size());
    }

    @Test
    void iteratesPropertiesAndElementsAsViews() {
        int entries = 0;
        for (Map.Entry<String, JsonView> entry : root.get("usage").properties()) {
            entries++;
            assertTrue(entry.getValue().isNumber());
        }
        assertEquals(2, entries);

        int elements = 0;
        for (JsonView choice : root.get("choices").elements()) {
            elements++;
            assertTrue(choice.isObject());
        }
        assertEquals(2, elements);

        // non-containers iterate empty, they do not throw
        assertFalse(root.get("model").properties().iterator().hasNext());
        assertFalse(root.get("model").elements().iterator().hasNext());
    }

    @Test
    void ofWrapsScalarsAndNullDirectly() {
        assertEquals("x", JsonView.of("x").asText());
        assertEquals(1L, JsonView.of(1).asLong());
        assertTrue(JsonView.of(null).isNull());
        // an unexpected Java type degrades to missing rather than throwing
        assertTrue(JsonView.of(new Object()).isMissing());
    }

}
