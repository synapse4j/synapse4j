package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProviderExtrasTest {

    @Test
    void putStoresTopLevelValue() {
        ProviderExtras extras = new ProviderExtras();

        ProviderExtras returned = extras.put("temperature", 0.5);

        assertSame(extras, returned);
        assertTrue(extras.contains("temperature"));
        assertEquals(0.5, extras.get("temperature"));
        assertEquals(1, extras.size());
        assertEquals(Map.of("temperature", 0.5), extras.nestedMap());
    }

    @Test
    void putStoresNestedValue() {
        ProviderExtras extras = new ProviderExtras();

        extras.put(List.of("metadata", "trace_id"), "abc");

        assertTrue(extras.contains("metadata", "trace_id"));
        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(1, extras.size());
        assertEquals(Map.of("metadata", Map.of("trace_id", "abc")), extras.nestedMap());
    }

    @Test
    void siblingPathsShareTheirParent() {
        ProviderExtras extras = new ProviderExtras();

        extras.put(List.of("a", "b"), 1);
        extras.put(List.of("a", "c"), 2);

        assertEquals(2, extras.size());
        assertEquals(Map.of("a", Map.of("b", 1, "c", 2)), extras.nestedMap());
    }

    @Test
    void separatorInsideKeyTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);

        assertEquals(1, extras.size());
        assertEquals(1, extras.get("a.b"));
        assertEquals(Map.of("a.b", 1), extras.nestedMap());
    }

    @Test
    void literalKeyCoexistsWithPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);
        extras.put("a", 2);

        assertEquals(2, extras.size());
        assertEquals(Map.of("a.b", 1, "a", 2), extras.nestedMap());
    }

    @Test
    void keyEscapeTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a\\b", 1);

        assertEquals(1, extras.get("a\\b"));
        assertEquals(Map.of("a\\b", 1), extras.nestedMap());
    }

    @Test
    void putOverwritesExistingPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a", 1);
        extras.put("a", 2);

        assertEquals(1, extras.size());
        assertEquals(2, extras.get("a"));
    }

    @Test
    void nullValueStored() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("safety", null);

        assertEquals(1, extras.size());
        assertTrue(extras.contains("safety"));
        assertNull(extras.get("safety"));
        assertTrue(extras.nestedMap().containsKey("safety"));
    }

    @Test
    void storedValueKeptByReference() {
        ProviderExtras extras = new ProviderExtras();
        List<String> stop = new ArrayList<>(List.of("alpha"));

        extras.put("stop", stop);
        stop.add("beta");

        assertEquals(List.of("alpha", "beta"), extras.get("stop"));
    }

    @Test
    void nestedPathClearsLeaf() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.put(List.of("a", "b"), 2);

        assertEquals(Map.of("a", Map.of("b", 2)), extras.nestedMap());
        assertNull(extras.get("a"));
    }

    @Test
    void shallowPathClearsDescendants() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 2);

        extras.put("a", 1);

        assertEquals(Map.of("a", 1), extras.nestedMap());
        assertNull(extras.get("a", "b"));
    }

    @Test
    void emptyPathRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(List.of(), 1));
    }

    @Test
    void emptyPathSegmentRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(List.of("a", ""), 1));
    }

    @Test
    void nullPathSegmentRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(Arrays.asList("a", null), 1));
    }

    @Test
    void putAllMergesOtherBag() {
        ProviderExtras extras = new ProviderExtras().put("a", 1).put("b", 2);
        ProviderExtras other = new ProviderExtras().put("b", 3).put("c", 4);

        ProviderExtras returned = extras.putAll(other);

        assertSame(extras, returned);
        assertEquals(Map.of("a", 1, "b", 3, "c", 4), extras.nestedMap());
        assertEquals(Map.of("b", 3, "c", 4), other.nestedMap());
    }

    @Test
    void putAllWinsOnOverlap() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);
        ProviderExtras other = new ProviderExtras().put("a", 2);

        extras.putAll(other);

        assertEquals(Map.of("a", 2), extras.nestedMap());
    }

    @Test
    void emptyBagNoOp() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.putAll(new ProviderExtras());

        assertEquals(Map.of("a", 1), extras.nestedMap());
    }

    @Test
    void putAllSelfNoOp() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertSame(extras, extras.putAll(extras));
        assertEquals(1, extras.size());
    }

    @Test
    void nestedMapFreshCopy() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        Map<String, Object> nested = extras.nestedMap();
        nested.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.nestedMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void innerNestedMapFreshCopy() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        Map<String, Object> inner = (Map<String, Object>) extras.nestedMap().get("a");
        inner.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.nestedMap());
    }

    @Test
    void bagsEqualBySameEntries() {
        ProviderExtras one = new ProviderExtras().put(List.of("a", "b"), 1);
        ProviderExtras two = new ProviderExtras().put(List.of("a", "b"), 1);

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one, new ProviderExtras().put(List.of("a", "b"), 2));
        assertNotEquals(one, new ProviderExtras());
    }

    @Test
    void freezeReturnsReadOnlyCopy() {
        ProviderExtras frozen = new ProviderExtras().put("temperature", 0.5).freeze();

        assertEquals(0.5, frozen.get("temperature"));
        assertSame(frozen, frozen.freeze());
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("top_p", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> frozen.putRaw("raw", 1));
        assertThrows(UnsupportedOperationException.class, () -> frozen.remove("temperature"));
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.putAll(new ProviderExtras().put("top_p", 1.0)));
    }

    @Test
    void frozenCopyIndependentEqual() {
        ProviderExtras extras = new ProviderExtras().put("temperature", 0.5);

        ProviderExtras frozen = extras.freeze();
        extras.put("top_p", 1.0);

        assertFalse(frozen.contains("top_p"));
        assertNotEquals(frozen, extras);
        assertEquals(new ProviderExtras().put("temperature", 0.5), frozen);
    }

    @Test
    void toStringShowsRawEntries() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        assertEquals("{a.b=1}", extras.toString());
    }

    @Test
    void removeDropsEntry() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertSame(extras, extras.remove("a"));

        assertTrue(extras.isEmpty());
        assertFalse(extras.contains("a"));
        assertNull(extras.get("a"));
    }

    @Test
    void removeUnsetPathNoOp() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.remove("b");

        assertEquals(1, extras.size());
        assertEquals(1, extras.get("a"));
    }

    @Test
    void removeTakesPathSegments() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc");

        extras.remove("metadata", "trace_id");

        assertTrue(extras.isEmpty());
    }

    @Test
    void removeTouchesExactPath() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc");

        extras.remove("metadata");

        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(1, extras.size());
    }

    @Test
    void removeRejectsInvalidPath() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertThrows(IllegalArgumentException.class, () -> extras.remove());
        assertThrows(IllegalArgumentException.class, () -> extras.remove((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> extras.remove(""));
        assertThrows(IllegalArgumentException.class, () -> extras.remove("a", null));

        assertEquals(1, extras.size());
    }

    @Test
    void putRawStoresAssembledKey() {
        ProviderExtras extras = new ProviderExtras();

        ProviderExtras returned = extras.putRaw("metadata.trace_id", "abc");

        assertSame(extras, returned);
        assertEquals(1, extras.size());
        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(Map.of("metadata", Map.of("trace_id", "abc")), extras.nestedMap());
    }

    @Test
    void putRawKeepsEscapedSeparator() {
        ProviderExtras extras = new ProviderExtras();

        extras.putRaw("a\\.b", 1);

        assertEquals(1, extras.size());
        assertEquals(Map.of("a.b", 1), extras.nestedMap());
    }

    @Test
    void putRawClearsOverlap() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        extras.putRaw("a", 2);

        assertEquals(1, extras.size());
        assertEquals(Map.of("a", 2), extras.nestedMap());
    }

    @Test
    void rawMapExposesAssembledKeys() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc").put("temperature",
                0.5);

        assertEquals(Map.of("metadata.trace_id", "abc", "temperature", 0.5), extras.rawMap());
    }

    @Test
    void rawMapIsReadOnly() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        Map<String, Object> raw = extras.rawMap();

        assertThrows(UnsupportedOperationException.class, () -> raw.put("b", 2));
        assertEquals(1, extras.size());
    }

    @Test
    void rawMapIsLive() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        Map<String, Object> raw = extras.rawMap();
        extras.put("b", 2);

        assertEquals(Map.of("a", 1, "b", 2), raw);
    }

    @Test
    void getRawReadsAssembledKeys() {
        // The counterpart of putRaw, so it addresses the keys rawMap spells rather than the path
        // segments get takes: a path and a key that spells the same text are two different entries.
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1).putRaw("a\\.b", 2);

        assertEquals(1, extras.getRaw("a.b"));
        assertEquals(2, extras.getRaw("a\\.b"));
        assertEquals(1, extras.get("a", "b"));
        assertNull(extras.getRaw("a"));
    }

    @Test
    void bagRoundTripsRawMap() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc").put("temperature",
                0.5);
        ProviderExtras restored = new ProviderExtras();

        extras.rawMap().forEach(restored::putRaw);

        assertEquals(extras, restored);
    }

    @ParameterizedTest(name = "mergeInto {0}")
    @MethodSource("mergeCases")
    void mergeIntoFollowsRules(String rule, List<Put> puts, Map<String, Object> members,
            Map<String, Object> expected) {
        ProviderExtras extras = new ProviderExtras();
        for (Put put : puts) {
            extras.put(put.path(), put.value());
        }
        Map<String, Object> target = new LinkedHashMap<>(members);

        ProviderExtras returned = extras.mergeInto(target);

        assertSame(extras, returned);
        assertEquals(expected, target);
    }

    /** Each rule the merge follows: the writes the bag makes, and the map they land in. */
    private static Stream<Arguments> mergeCases() {
        return Stream.of(
                Arguments.of("sets paths the map does not have yet",
                        List.of(new Put(List.of("metadata", "trace_id"), "abc")), Map.of(),
                        Map.of("metadata", Map.of("trace_id", "abc"))),
                Arguments.of("lets the bag win over a value at the same position",
                        List.of(new Put(List.of("temperature"), 0.7)), Map.of("temperature", 0.3),
                        Map.of("temperature", 0.7)),
                Arguments.of("leaves members the bag never sets",
                        List.of(new Put(List.of("temperature"), 0.7)),
                        Map.of("model", "gpt-4o", "temperature", 0.3),
                        Map.of("model", "gpt-4o", "temperature", 0.7)),
                Arguments.of("walks through a container and keeps its other members",
                        List.of(new Put(List.of("response_format", "json_schema", "strict"), true)),
                        mapOf("response_format",
                                mapOf("type", "json_schema", "json_schema", mapOf("name", "response"))),
                        mapOf("response_format",
                                mapOf("type", "json_schema", "json_schema",
                                        mapOf("name", "response", "strict", true)))),
                Arguments.of("discards a leaf blocking the way",
                        List.of(new Put(List.of("content", "cache_control"), "ephemeral")),
                        Map.of("content", "hello"), Map.of("content", Map.of("cache_control", "ephemeral"))),
                Arguments.of("replaces the whole position when the path stops there",
                        List.of(new Put(List.of("response_format"), Map.of("type", "text"))),
                        Map.of("response_format", Map.of("type", "json_schema", "name", "response")),
                        Map.of("response_format", Map.of("type", "text"))),
                Arguments.of("discards a list blocking the way", List.of(new Put(List.of("a", "b"), 1)),
                        Map.of("a", List.of(1, 2)), Map.of("a", Map.of("b", 1))),
                Arguments.of("with an empty bag changes nothing", List.of(), Map.of("model", "gpt-4o"),
                        Map.of("model", "gpt-4o")),
                Arguments.of("accepts a null value", List.of(new Put(List.of("stop"), null)), Map.of(),
                        mapOf("stop", null)));
    }

    /**
     * A mutable map, which the merge needs for every container it walks into — {@link Map#of} refuses
     * both a later write and the null value a bag may hold.
     */
    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** One write into the bag: the path it lands on, and the value it carries. */
    private record Put(List<String> path, Object value) {
    }

    @Test
    void copyThenRemoveDropsInherited() {
        ProviderExtras defaults = new ProviderExtras().put("service_tier", "flex").put("temperature", 1.0).put("top_p",
                0.5);
        ProviderExtras effective = new ProviderExtras().putAll(defaults);

        effective.remove("service_tier").remove("top_p");

        assertEquals(3, defaults.size());
        assertEquals(Map.of("temperature", 1.0), effective.nestedMap());
    }

}
