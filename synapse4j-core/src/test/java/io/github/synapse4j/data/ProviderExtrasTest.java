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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProviderExtrasTest {

    @Test
    void newBagIsEmpty() {
        ProviderExtras extras = new ProviderExtras();

        assertTrue(extras.isEmpty());
        assertEquals(0, extras.size());
        assertEquals(Map.of(), extras.toNestedMap());
        assertFalse(extras.contains("anything"));
        assertNull(extras.get("anything"));
    }

    @Test
    void putStoresAValueAtTheTopLevel() {
        ProviderExtras extras = new ProviderExtras();

        ProviderExtras returned = extras.put("temperature", 0.5);

        assertSame(extras, returned);
        assertTrue(extras.contains("temperature"));
        assertEquals(0.5, extras.get("temperature"));
        assertEquals(1, extras.size());
        assertEquals(Map.of("temperature", 0.5), extras.toNestedMap());
    }

    @Test
    void putStoresAValueAtANestedPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put(List.of("metadata", "trace_id"), "abc");

        assertTrue(extras.contains("metadata", "trace_id"));
        assertEquals("abc", extras.get("metadata", "trace_id"));
        assertEquals(1, extras.size());
        assertEquals(Map.of("metadata", Map.of("trace_id", "abc")), extras.toNestedMap());
    }

    @Test
    void siblingPathsShareTheirParent() {
        ProviderExtras extras = new ProviderExtras();

        extras.put(List.of("a", "b"), 1);
        extras.put(List.of("a", "c"), 2);

        assertEquals(2, extras.size());
        assertEquals(Map.of("a", Map.of("b", 1, "c", 2)), extras.toNestedMap());
    }

    @Test
    void aSeparatorInsideAKeyIsTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);

        assertEquals(1, extras.size());
        assertEquals(1, extras.get("a.b"));
        assertEquals(Map.of("a.b", 1), extras.toNestedMap());
    }

    @Test
    void aLiteralKeyDoesNotConflictWithANestedPath() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a.b", 1);
        extras.put("a", 2);

        assertEquals(2, extras.size());
        assertEquals(Map.of("a.b", 1, "a", 2), extras.toNestedMap());
    }

    @Test
    void anEscapeCharacterInsideAKeyIsTakenLiterally() {
        ProviderExtras extras = new ProviderExtras();

        extras.put("a\\b", 1);

        assertEquals(1, extras.get("a\\b"));
        assertEquals(Map.of("a\\b", 1), extras.toNestedMap());
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
        assertTrue(extras.toNestedMap().containsKey("safety"));
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
    void aPathConflictingWithAnExistingLeafIsRejected() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        assertThrows(IllegalArgumentException.class, () -> extras.put(List.of("a", "b"), 2));
    }

    @Test
    void aPathConflictingWithAnExistingNestedPathIsRejected() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 2);

        assertThrows(IllegalArgumentException.class, () -> extras.put("a", 1));
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
        assertEquals(Map.of("a", 1, "b", 3, "c", 4), extras.toNestedMap());
        assertEquals(Map.of("b", 3, "c", 4), other.toNestedMap());
    }

    @Test
    void putAllRejectsAConflictingPath() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);
        ProviderExtras other = new ProviderExtras().put("a", 2);

        assertThrows(IllegalArgumentException.class, () -> extras.putAll(other));
    }

    @Test
    void putAllWithAnEmptyBagChangesNothing() {
        ProviderExtras extras = new ProviderExtras().put("a", 1);

        extras.putAll(new ProviderExtras());

        assertEquals(Map.of("a", 1), extras.toNestedMap());
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

        Map<String, Object> nested = extras.toNestedMap();
        nested.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.toNestedMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNestedMapOfANestedPathIsAFreshCopyToo() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        Map<String, Object> inner = (Map<String, Object>) extras.toNestedMap().get("a");
        inner.put("c", 2);

        assertEquals(Map.of("a", Map.of("b", 1)), extras.toNestedMap());
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
    void toStringShowsTheNestedShape() {
        ProviderExtras extras = new ProviderExtras().put(List.of("a", "b"), 1);

        assertEquals("ProviderExtras{a={b=1}}", extras.toString());
    }

}
