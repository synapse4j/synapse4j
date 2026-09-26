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

import org.junit.jupiter.api.Test;

class ProviderExtrasTest {

    @Test
    void putStoresAValueAtTheTopLevel() {
        ProviderExtras extras = new ProviderExtras();

        ProviderExtras returned = extras.put("temperature", 0.5);

        assertSame(extras, returned);
        assertTrue(extras.contains("temperature"));
        assertEquals(0.5, extras.get("temperature"));
        assertEquals(1, extras.size());
        assertEquals(Map.of("temperature", 0.5), extras.nestedMap());
    }

    @Test
    void putStoresAValueAtANestedPath() {
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
    void aSeparatorInsideAKeyIsTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);

        assertEquals(1, extras.size());
        assertEquals(1, extras.get("a.b"));
        assertEquals(Map.of("a.b", 1), extras.nestedMap());
    }

    @Test
    void aLiteralKeyDoesNotConflictWithANestedPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);
        extras.put("a", 2);

        assertEquals(2, extras.size());
        assertEquals(Map.of("a.b", 1, "a", 2), extras.nestedMap());
    }

    @Test
    void anEscapeCharacterInsideAKeyIsTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a\\b", 1);

        assertEquals(1, extras.get("a\\b"));
        assertEquals(Map.of("a\\b", 1), extras.nestedMap());
    }

    @Test
    void putOverwritesAnExistingPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a", 1);
        extras.put("a", 2);

        assertEquals(1, extras.size());
        assertEquals(2, extras.get("a"));
    }

    @Test
    void aNullValueIsStored() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("safety", null);

        assertEquals(1, extras.size());
        assertTrue(extras.contains("safety"));
        assertNull(extras.get("safety"));
        assertTrue(extras.nestedMap().containsKey("safety"));
    }

    @Test
    void aStoredValueIsKeptByReference() {
        ProviderExtras extras = new ProviderExtras();
        List<String> stop = new ArrayList<>(List.of("alpha"));

        extras.put("stop", stop);
        stop.add("beta");

        assertEquals(List.of("alpha", "beta"), extras.get("stop"));
    }

    @Test
    void settingAPathUnderAStoredLeafClearsTheLeaf() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.put(List.of("a", "b"), 2);

        assertEquals(Map.of("a", Map.of("b", 2)), extras.nestedMap());
        assertNull(extras.get("a"));
    }

    @Test
    void settingAPathOverStoredDescendantsClearsThem() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 2);

        extras.put("a", 1);

        assertEquals(Map.of("a", 1), extras.nestedMap());
        assertNull(extras.get("a", "b"));
    }

    @Test
    void anEmptyPathIsRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(List.of(), 1));
    }

    @Test
    void anEmptyPathSegmentIsRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(List.of("a", ""), 1));
    }

    @Test
    void aNullPathSegmentIsRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(IllegalArgumentException.class, () -> extras.put(Arrays.asList("a", null), 1));
    }

    @Test
    void aNullKeyIsRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(NullPointerException.class, () -> extras.put((String) null, 1));
    }

    @Test
    void aNullPathIsRejected() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(NullPointerException.class, () -> extras.put((List<String>) null, 1));
    }

    @Test
    void putAllMergesTheOtherBag() {
        ProviderExtras extras = new ProviderExtras().put("a", 1).put("b", 2);
        ProviderExtras other = new ProviderExtras().put("b", 3).put("c", 4);

        ProviderExtras returned = extras.putAll(other);

        assertSame(extras, returned);
        assertEquals(Map.of("a", 1, "b", 3, "c", 4), extras.nestedMap());
        assertEquals(Map.of("b", 3, "c", 4), other.nestedMap());
    }

    @Test
    void putAllWinsWhereThePathsOverlap() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);
        ProviderExtras other = new ProviderExtras().put("a", 2);

        extras.putAll(other);

        assertEquals(Map.of("a", 2), extras.nestedMap());
    }

    @Test
    void putAllWithAnEmptyBagChangesNothing() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.putAll(new ProviderExtras());

        assertEquals(Map.of("a", 1), extras.nestedMap());
    }

    @Test
    void putAllWithThisBagIsANoOp() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertSame(extras, extras.putAll(extras));
        assertEquals(1, extras.size());
    }

    @Test
    void putAllRejectsNull() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(NullPointerException.class, () -> extras.putAll(null));
    }

    @Test
    void theNestedMapIsAFreshCopy() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        Map<String, Object> nested = extras.nestedMap();
        nested.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.nestedMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNestedMapOfANestedPathIsAFreshCopyToo() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        Map<String, Object> inner = (Map<String, Object>) extras.nestedMap().get("a");
        inner.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.nestedMap());
    }

    @Test
    void bagsWithTheSameEntriesAreEqual() {
        ProviderExtras one = new ProviderExtras().put(List.of("a", "b"), 1);
        ProviderExtras two = new ProviderExtras().put(List.of("a", "b"), 1);

        assertEquals(one, one);
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one, new ProviderExtras().put(List.of("a", "b"), 2));
        assertNotEquals(one, new ProviderExtras());
        assertNotEquals(one, null);
    }

    @Test
    void toStringShowsTheRawEntries() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        assertEquals("ProviderExtras{a.b=1}", extras.toString());
    }

    @Test
    void removeDropsTheEntry() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertSame(extras, extras.remove("a"));

        assertTrue(extras.isEmpty());
        assertFalse(extras.contains("a"));
        assertNull(extras.get("a"));
    }

    @Test
    void removeOfAnUnsetPathChangesNothing() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.remove("b");

        assertEquals(1, extras.size());
        assertEquals(1, extras.get("a"));
    }

    @Test
    void removeTakesAPathOfSegments() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc");

        extras.remove("metadata", "trace_id");

        assertTrue(extras.isEmpty());
    }

    @Test
    void removeOnlyTouchesTheExactPath() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc");

        extras.remove("metadata");

        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(1, extras.size());
    }

    @Test
    void removeRejectsAnEmptyOrInvalidPath() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertThrows(IllegalArgumentException.class, () -> extras.remove());
        assertThrows(IllegalArgumentException.class, () -> extras.remove((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> extras.remove(""));
        assertThrows(IllegalArgumentException.class, () -> extras.remove("a", null));

        assertEquals(1, extras.size());
    }

    @Test
    void putRawStoresAnAssembledKeyAsGiven() {
        ProviderExtras extras = new ProviderExtras();

        ProviderExtras returned = extras.putRaw("metadata.trace_id", "abc");

        assertSame(extras, returned);
        assertEquals(1, extras.size());
        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(Map.of("metadata", Map.of("trace_id", "abc")), extras.nestedMap());
    }

    @Test
    void putRawKeepsAnEscapedSeparatorInTheKey() {
        ProviderExtras extras = new ProviderExtras();

        extras.putRaw("a\\.b", 1);

        assertEquals(1, extras.size());
        assertEquals(Map.of("a.b", 1), extras.nestedMap());
    }

    @Test
    void putRawClearsWhatItOverlaps() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        extras.putRaw("a", 2);

        assertEquals(1, extras.size());
        assertEquals(Map.of("a", 2), extras.nestedMap());
    }

    @Test
    void putRawRejectsANullKey() {
        ProviderExtras extras = new ProviderExtras();

        assertThrows(NullPointerException.class, () -> extras.putRaw(null, 1));
    }

    @Test
    void rawMapExposesTheEntriesUnderTheirAssembledKeys() {
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
    void aBagRoundTripsThroughItsRawMap() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc").put("temperature",
                0.5);
        ProviderExtras restored = new ProviderExtras();

        extras.rawMap().forEach(restored::putRaw);

        assertEquals(extras, restored);
    }

    @Test
    void mergeIntoSetsPathsTheMapDoesNotHaveYet() {
        ProviderExtras extras = new ProviderExtras().put(List.of("metadata", "trace_id"), "abc");
        Map<String, Object> members = new LinkedHashMap<>();

        ProviderExtras returned = extras.mergeInto(members);

        assertSame(extras, returned);
        assertEquals(Map.of("metadata", Map.of("trace_id", "abc")), members);
    }

    @Test
    void mergeIntoLetsTheBagWinOverAValueAtTheSamePosition() {
        ProviderExtras extras = new ProviderExtras().put("temperature", 0.7);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("temperature", 0.3);

        extras.mergeInto(members);

        assertEquals(Map.of("temperature", 0.7), members);
    }

    @Test
    void mergeIntoLeavesMembersTheBagNeverSets() {
        ProviderExtras extras = new ProviderExtras().put("temperature", 0.7);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("model", "gpt-4o");
        members.put("temperature", 0.3);

        extras.mergeInto(members);

        assertEquals(Map.of("model", "gpt-4o", "temperature", 0.7), members);
    }

    @Test
    void mergeIntoWalksThroughAContainerAndKeepsItsOtherMembers() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "response");
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", schema);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("response_format", format);
        ProviderExtras extras = new ProviderExtras().put(List.of("response_format", "json_schema", "strict"), true);

        extras.mergeInto(members);

        assertEquals(Map.of("type", "json_schema", "json_schema", Map.of("name", "response", "strict", true)),
                members.get("response_format"));
    }

    @Test
    void mergeIntoDiscardsALeafBlockingTheWay() {
        ProviderExtras extras = new ProviderExtras().put(List.of("content", "cache_control"), "ephemeral");
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("content", "hello");

        extras.mergeInto(members);

        assertEquals(Map.of("content", Map.of("cache_control", "ephemeral")), members);
    }

    @Test
    void mergeIntoReplacesTheWholePositionWhenThePathStopsThere() {
        ProviderExtras extras = new ProviderExtras().put("response_format", Map.of("type", "text"));
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("response_format", Map.of("type", "json_schema", "name", "response"));

        extras.mergeInto(members);

        assertEquals(Map.of("response_format", Map.of("type", "text")), members);
    }

    @Test
    void mergeIntoDiscardsAListBlockingTheWay() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("a", List.of(1, 2));

        extras.mergeInto(members);

        assertEquals(Map.of("a", Map.of("b", 1)), members);
    }

    @Test
    void mergeIntoWithAnEmptyBagChangesNothing() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("model", "gpt-4o");

        new ProviderExtras().mergeInto(members);

        assertEquals(Map.of("model", "gpt-4o"), members);
    }

    @Test
    void mergeIntoAcceptsANullValue() {
        ProviderExtras extras = new ProviderExtras().put("stop", null);
        Map<String, Object> members = new LinkedHashMap<>();

        extras.mergeInto(members);

        assertTrue(members.containsKey("stop"));
        assertNull(members.get("stop"));
    }

    @Test
    void mergeIntoRejectsANullMap() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertThrows(NullPointerException.class, () -> extras.mergeInto(null));
    }

    @Test
    void copyingABagAndRemovingIsHowInheritedValuesAreDropped() {
        ProviderExtras defaults = new ProviderExtras().put("service_tier", "flex").put("temperature", 1.0).put("top_p",
                0.5);
        ProviderExtras effective = new ProviderExtras().putAll(defaults);

        effective.remove("service_tier").remove("top_p");

        assertEquals(3, defaults.size());
        assertEquals(Map.of("temperature", 1.0), effective.nestedMap());
    }

}
