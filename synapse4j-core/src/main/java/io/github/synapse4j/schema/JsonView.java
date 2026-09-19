package io.github.synapse4j.schema;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A read-only, null-safe view over decoded JSON data.
 *
 * <p>
 * Decoding JSON through {@link JsonCodec} yields plain JDK shapes — maps, lists, strings, numbers,
 * booleans — and navigating those raw structures forces an instanceof-or-cast at every step. This
 * view centralizes that logic: every accessor is null-safe, a failed navigation ({@link #get(String)
 * missing key}, {@link #get(int) out-of-range index}, type mismatch) yields a shared missing
 * sentinel instead of {@code null}, so call sites neither check for {@code null} nor catch
 * exceptions nor inspect types. Missing and JSON {@code null} are deliberately distinguishable
 * ({@link #isMissing()} vs {@link #isNull()}) — for example when everything not mapped from a
 * provider response is to be preserved into extras.
 *
 * <p>
 * The view wraps whatever {@link JsonCodec#decode(String, java.lang.reflect.Type)} returns for a
 * map target and holds no state of its own; it is safe to share and discard freely.
 */
public class JsonView {

    private static final JsonView MISSING = new JsonView(null, true);
    private static final JsonView NULL = new JsonView(null, false);

    private final Object value;
    private final boolean missing;

    private JsonView(Object value, boolean missing) {
        this.value = value;
        this.missing = missing;
    }

    /**
     * Wraps the result of decoding a JSON document.
     *
     * @param decoded a {@code Map<String,?>}, {@code List<?>}, {@code String}, {@code Number},
     *                    {@code Boolean}, or {@code null} (a JSON null); anything else is treated as missing
     * @return a view over it; never {@code null}
     */
    public static JsonView of(Object decoded) {
        if (decoded == null) {
            return NULL;
        }
        if (decoded instanceof Map<?, ?> || decoded instanceof List<?> || decoded instanceof String
                || decoded instanceof Number || decoded instanceof Boolean) {
            return new JsonView(decoded, false);
        }
        return MISSING;
    }

    /** Returns whether this view is the sentinel produced by a failed navigation. */
    public boolean isMissing() {
        return missing;
    }

    /** Returns whether this node is a JSON null. */
    public boolean isNull() {
        return !missing && value == null;
    }

    public boolean isObject() {
        return !missing && value instanceof Map<?, ?>;
    }

    public boolean isArray() {
        return !missing && value instanceof List<?>;
    }

    public boolean isText() {
        return !missing && value instanceof String;
    }

    public boolean isNumber() {
        return !missing && value instanceof Number;
    }

    public boolean isBoolean() {
        return !missing && value instanceof Boolean;
    }

    /**
     * The textual value: the string itself for text nodes, the plain spelling for numbers and
     * booleans; {@code null} for everything else, including missing.
     */
    public String asText() {
        if (isText()) {
            return (String) value;
        }
        if (isNumber() || isBoolean()) {
            return String.valueOf(value);
        }
        return null;
    }

    /** The numeric value as a long; {@code null} unless this node is a number. */
    public Long asLong() {
        return isNumber() ? ((Number) value).longValue() : null;
    }

    /** The numeric value as a double; {@code null} unless this node is a number. */
    public Double asDouble() {
        return isNumber() ? ((Number) value).doubleValue() : null;
    }

    /** The boolean value; {@code null} unless this node is a boolean. */
    public Boolean asBoolean() {
        return isBoolean() ? (Boolean) value : null;
    }

    /**
     * The named child of an object node; the missing sentinel when this node is not an object or
     * has no such key — never {@code null}, and navigation on the sentinel keeps yielding it.
     */
    public JsonView get(String name) {
        if (isObject() && ((Map<?, ?>) value).containsKey(name)) {
            return of(((Map<?, ?>) value).get(name));
        }
        return MISSING;
    }

    /**
     * The indexed child of an array node; the missing sentinel when this node is not an array or
     * the index is out of range — never {@code null}.
     */
    public JsonView get(int index) {
        if (isArray()) {
            List<?> list = (List<?>) value;
            if (index >= 0 && index < list.size()) {
                return of(list.get(index));
            }
        }
        return MISSING;
    }

    /** The number of keys (object) or elements (array); {@code 0} for any other node. */
    public int size() {
        if (isObject()) {
            return ((Map<?, ?>) value).size();
        }
        if (isArray()) {
            return ((List<?>) value).size();
        }
        return 0;
    }

    /**
     * Iterates the object's entries as child views. Empty unless this node is an object.
     */
    public Iterable<Map.Entry<String, JsonView>> properties() {
        return () -> new Iterator<Map.Entry<String, JsonView>>() {

            private final Iterator<? extends Map.Entry<String, ?>> iterator = entries().iterator();

            private java.util.Set<? extends Map.Entry<String, ?>> entries() {
                if (isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, ?> map = (Map<String, ?>) value;
                    return map.entrySet();
                }
                return Map.<String, Object>of().entrySet();
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public Map.Entry<String, JsonView> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Map.Entry<String, ?> entry = iterator.next();
                return Map.entry(entry.getKey(), of(entry.getValue()));
            }
        };
    }

    /** Iterates the array's elements as child views. Empty unless this node is an array. */
    public Iterable<JsonView> elements() {
        return () -> new Iterator<JsonView>() {

            private final Iterator<?> iterator = items().iterator();

            private List<?> items() {
                if (isArray()) {
                    return (List<?>) value;
                }
                return List.of();
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public JsonView next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return of(iterator.next());
            }
        };
    }

    @Override
    public String toString() {
        return missing ? "<missing>" : String.valueOf(value);
    }

}
