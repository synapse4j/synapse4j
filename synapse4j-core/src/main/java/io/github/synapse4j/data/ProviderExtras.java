package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.EqualsAndHashCode;

/**
 * Extra, provider-specific fields attached to a single node of a request (the request itself, a
 * message, a content part or a tool declaration) and merged into the outgoing payload when the
 * request is sent.
 *
 * <p>
 * Values are addressed by a path of one or more segments. Segments are joined internally with a
 * separator and escaped where necessary, so callers never write the delimiter or the escaping
 * themselves. The internal key syntax is deliberately <em>not</em> part of the public contract and
 * may change.
 *
 * <p>
 * A path may address either a leaf or a whole subtree: the value is stored opaquely and never
 * inspected, so it may be a scalar, a collection or an arbitrary object to be serialized by the
 * configured JSON codec. Setting a path that is a prefix of an already-set path (or vice versa) is
 * rejected, because the resulting structure would be ambiguous.
 *
 * <p>
 * Instances are mutable: this is an accumulating bag, in the spirit of {@link Map}, not a value
 * object. The caller owns the instance and is responsible for populating it. A node that carries a
 * bag never shares it — each node creates its own — so content moves between bags by copying
 * ({@link #putAll(ProviderExtras)}). Instances are not thread-safe.
 *
 * <p>
 * Values are kept by reference and are never copied when stored: mutating a stored value (a
 * collection, say) afterwards is visible to whoever reads the bag back, including the
 * serialization path.
 */
@EqualsAndHashCode
public class ProviderExtras {

    private static final char SEPARATOR = '.';
    private static final char ESCAPE = '\\';

    /**
     * Flat storage. The key is the already-escaped path; values are opaque. Not exposed: the key
     * syntax is an implementation detail.
     */
    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Returns the number of set paths.
     *
     * @return the number of entries
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns whether no path has been set.
     *
     * @return {@code true} if this bag is empty
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns whether a value is set at the given path.
     *
     * @param path one or more path segments; must not be empty
     * @return {@code true} if a value is set at that path
     */
    public boolean contains(String... path) {
        return values.containsKey(encode(path));
    }

    /**
     * Returns the value set at the given path.
     *
     * @param path one or more path segments; must not be empty
     * @return the value, or {@code null} if nothing is set at that path
     */
    public Object get(String... path) {
        return values.get(encode(path));
    }

    /**
     * Removes the entry set at the given path.
     *
     * <p>
     * Only that exact path goes: an entry set deeper (removing {@code a} while {@code a.b} is set) is
     * a separate entry and stays. The two never coexist in the first place, because setting one while
     * the other is present is rejected.
     *
     * @param path one or more path segments; must not be empty
     * @return this bag
     */
    public ProviderExtras remove(String... path) {
        values.remove(encode(path));
        return this;
    }

    /**
     * Sets a value at a single-segment path, i.e. at the top level of the node, replacing any value
     * set there before.
     *
     * <p>
     * The key is one field name: any separator character inside it is treated literally rather than
     * as a path boundary.
     *
     * @param key   the field name; must not be {@code null} or empty
     * @param value the value; stored as-is, may be {@code null}
     * @return this bag
     */
    public ProviderExtras put(String key, Object value) {
        return putPath(value, List.of(key));
    }

    /**
     * Sets a value at a path of one or more segments, replacing any value set there before.
     *
     * @param path  the path segments; must not be {@code null} or empty, and no segment may be
     *                  {@code null} or empty
     * @param value the value; stored as-is, may be {@code null}
     * @return this bag
     */
    public ProviderExtras put(List<String> path, Object value) {
        return putPath(value, path);
    }

    /**
     * Merges another bag into this one. Entries of the other bag win on equal paths.
     *
     * @param other the bag to merge in; must not be {@code null}
     * @return this bag
     */
    public ProviderExtras putAll(ProviderExtras other) {
        Objects.requireNonNull(other, "other must not be null");
        if (other == this) {
            return this;
        }
        for (Map.Entry<String, Object> entry : other.values.entrySet()) {
            requireNoConflict(values, entry.getKey());
            values.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Returns this bag as a nested map, with one level per path segment.
     *
     * <p>
     * This is the form consumed by provider adapters when merging the extras into the outgoing
     * payload. The result is a fresh, mutable copy owned by the caller; changing it does not affect
     * this instance.
     *
     * @return the nested view; never {@code null}, empty if nothing is set
     */
    public Map<String, Object> toNestedMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            List<String> segments = decode(entry.getKey());
            Map<String, Object> node = root;
            for (int i = 0; i < segments.size() - 1; i++) {
                Object child = node.get(segments.get(i));
                if (!(child instanceof Map)) {
                    child = new LinkedHashMap<String, Object>();
                    node.put(segments.get(i), child);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> childMap = (Map<String, Object>) child;
                node = childMap;
            }
            node.put(segments.get(segments.size() - 1), entry.getValue());
        }
        return root;
    }

    @Override
    public String toString() {
        // Rendered as the nested view, which is what a reader wants; @ToString cannot produce it,
        // because it always prints the member name next to the value.
        return "ProviderExtras" + toNestedMap();
    }

    private ProviderExtras putPath(Object value, List<String> path) {
        Objects.requireNonNull(path, "path must not be null");
        String key = encode(path.toArray(new String[0]));
        requireNoConflict(values, key);
        values.put(key, value);
        return this;
    }

    /**
     * Rejects a path that would make the structure ambiguous, i.e. one that is an ancestor or a
     * descendant of an already-set path.
     */
    private static void requireNoConflict(Map<String, Object> existing, String key) {
        String descendants = key + SEPARATOR;
        for (String other : existing.keySet()) {
            if (other.equals(key)) {
                continue;
            }
            if (other.startsWith(descendants) || key.startsWith(other + SEPARATOR)) {
                throw new IllegalArgumentException("path " + decode(key)
                        + " conflicts with the already-set path " + decode(other));
            }
        }
    }

    private static String encode(String[] path) {
        if (path == null || path.length == 0) {
            throw new IllegalArgumentException("path must not be empty");
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i > 0) {
                key.append(SEPARATOR);
            }
            appendEscaped(key, path[i]);
        }
        return key.toString();
    }

    private static void appendEscaped(StringBuilder target, String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("path segment must not be null");
        }
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("path segment must not be empty");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == SEPARATOR || c == ESCAPE) {
                target.append(ESCAPE);
            }
            target.append(c);
        }
    }

    private static List<String> decode(String key) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (escaped) {
                segment.append(c);
                escaped = false;
            } else if (c == ESCAPE) {
                escaped = true;
            } else if (c == SEPARATOR) {
                segments.add(segment.toString());
                segment.setLength(0);
            } else {
                segment.append(c);
            }
        }
        segments.add(segment.toString());
        return segments;
    }

}
