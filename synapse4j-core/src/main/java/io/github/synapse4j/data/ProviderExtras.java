package io.github.synapse4j.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.EqualsAndHashCode;
import lombok.NonNull;

/**
 * Extra, provider-specific fields attached to a single node of a request (the request itself, a
 * message, a content part or a tool declaration) and merged into the outgoing payload when the
 * request is sent.
 *
 * <p>
 * A provider module assembles its own payload one object at a time: a map for the object a node
 * becomes, the members this module models written straight into it, and the node's bag merged over
 * them with {@link #mergeInto(Map)}. Merging is where the two meet — an entry that overlaps one of
 * the module's own replaces it, so the caller's value is the one that goes out, as one member of
 * that name rather than two; a member the bag never sets is left as the module wrote it.
 *
 * <p>
 * A value is addressed either by a path of one or more segments or by a key the caller has assembled
 * itself, and the two are the same string. Segments are joined with {@code .}, and a segment that
 * contains {@code .} or {@code \} escapes that character with {@code \}, so a segment holding a dot
 * is written {@code a\.b}. {@link #rawMap()} hands the entries out under exactly that spelling and
 * {@link #putRaw(String, Object)} takes one back as given, which is how a caller serializes a bag
 * somewhere and restores it later.
 *
 * <p>
 * A path may address either a leaf or a whole subtree: the value is stored opaquely and never
 * inspected, so it may be a scalar, a collection or an arbitrary object to be serialized by the
 * configured JSON codec. The names inside such an object are whatever that codec produces, so a
 * value the protocol spells differently belongs in a map instead.
 *
 * <p>
 * Setting a path clears whatever stands in the way of it — the entries it would contain, and the
 * entry it would sit inside — so a path replaces that position in the tree, the way a map's put
 * replaces a key, rather than being refused for overlapping what is already there.
 *
 * <p>
 * A path segment becomes a key of the outgoing object exactly as written: the caller spells the
 * provider's own wire name, so nothing is renamed, case-converted or otherwise adapted to the shape
 * of this library. The only structure the merge adds is the nesting the path describes.
 *
 * <p>
 * Where the payload's own fields land is the module's business, not this bag's: which names a
 * protocol uses, and at which nesting, is the protocol's to know. A path set here addresses the same
 * tree the module writes into, so a field of the provider's that sits inside one of the module's own
 * objects is set by that path and lands there.
 *
 * <p>
 * Instances are mutable: this is an accumulating bag, in the spirit of {@link Map}, not a value
 * object. The caller owns the instance and is responsible for populating it. A node that carries a
 * bag never shares it with another — each one sets its own — so content moves between bags by
 * copying ({@link #putAll(ProviderExtras)}). Instances are not thread-safe.
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
     * Flat storage, keyed by the assembled path. Handed out read-only through {@link #rawMap()}.
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
    public @Nullable Object get(String... path) {
        return values.get(encode(path));
    }

    /**
     * Removes the entry set at the given path.
     *
     * <p>
     * Only that exact path goes: an entry set deeper (removing {@code a} while {@code a.b} is set) is
     * a separate entry and stays.
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
    public ProviderExtras put(@NonNull String key, @Nullable Object value) {
        return putPath(value, List.of(key));
    }

    /**
     * Sets a value at a path of one or more segments, replacing any value set there before. An
     * ancestor stored as a leaf, or descendants under this path, are cleared in the same call.
     *
     * @param path  the path segments; must not be {@code null} or empty, and no segment may be
     *                  {@code null} or empty
     * @param value the value; stored as-is, may be {@code null}
     * @return this bag
     */
    public ProviderExtras put(@NonNull List<String> path, @Nullable Object value) {
        return putPath(value, path);
    }

    /**
     * Sets a value under a key the caller has already assembled, stored exactly as given: the key is
     * neither split nor escaped, and its shape is not checked.
     *
     * <p>
     * This is the writing counterpart of {@link #rawMap()}: an entry set here is read back from that
     * view, not through {@link #get(String...)}, which addresses path segments and so spells the
     * same tree differently.
     *
     * @param key   the assembled key; must not be {@code null}
     * @param value the value; stored as-is, may be {@code null}
     * @return this bag
     */
    public ProviderExtras putRaw(@NonNull String key, @Nullable Object value) {
        clearAround(key);
        values.put(key, value);
        return this;
    }

    /**
     * Merges another bag into this one. Entries of the other bag win where the paths are equal or
     * overlap.
     *
     * @param other the bag to merge in; must not be {@code null}
     * @return this bag
     */
    public ProviderExtras putAll(@NonNull ProviderExtras other) {
        if (other == this) {
            return this;
        }
        for (Map.Entry<String, Object> entry : other.values.entrySet()) {
            clearAround(entry.getKey());
            values.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Returns a read-only view of the entries under their assembled keys — the form
     * {@link #putRaw(String, Object)} accepts, and the one an entry set with an assembled key is
     * read back from.
     *
     * <p>
     * The view is live: entries set afterwards appear in it. Writing through it is refused, and the
     * values in it are the ones stored, by reference.
     *
     * @return the read-only view; never {@code null}, empty if nothing is set
     */
    public Map<String, Object> rawMap() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns this bag as a nested map, with one level per path segment.
     *
     * <p>
     * This is the form consumed by provider adapters when merging the extras into the outgoing
     * payload. The result is a fresh, mutable structure owned by the caller; changing the structure
     * does not affect this instance. The values in it are the ones stored, by reference.
     *
     * @return the nested view; never {@code null}, empty if nothing is set
     */
    public Map<String, Object> nestedMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        mergeInto(root);
        return root;
    }

    /**
     * Merges this bag into a tree the caller already built. Each path finds its way down and sets
     * itself, replacing whatever stands in the way of it: a leaf blocking the way is discarded, a
     * container on the way is walked through, the position itself is overwritten.
     *
     * <p>
     * The bag wins everywhere it sets a path and nowhere else: a member it never sets keeps the value
     * already in the map. Paths {@code a} and {@code a.b} are distinct positions, so setting
     * {@code a} replaces that whole value, while setting {@code a.b} reaches into it and leaves its
     * other members alone.
     *
     * <p>
     * Two bags may be merged into one map, in the order the module chooses. Each is applied over
     * what is already there, so the one merged later is the one that goes out.
     *
     * @param members the tree to write into; the containers it already holds must be mutable
     * @return this bag
     */
    public ProviderExtras mergeInto(@NonNull Map<String, Object> members) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            List<String> segments = decode(entry.getKey());
            Map<String, Object> node = members;
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
        return this;
    }

    @Override
    public String toString() {
        // Rendered as the raw entries, the spelling the caller assembles; @ToString cannot produce
        // it, because it always prints the member name next to the value.
        return "ProviderExtras" + rawMap();
    }

    private ProviderExtras putPath(Object value, @NonNull List<String> path) {
        String key = encode(path.toArray(new String[0]));
        clearAround(key);
        values.put(key, value);
        return this;
    }

    /**
     * Clears whatever stands in the way of a path being set: the entries it would contain, and the
     * entry it would sit inside. Setting a path replaces that position in the tree the way a map's
     * put replaces a key, rather than being refused for overlapping what is already there.
     */
    private void clearAround(String key) {
        String descendants = key + SEPARATOR;
        values.keySet()
                .removeIf(other -> !other.equals(key)
                        && (other.startsWith(descendants) || key.startsWith(other + SEPARATOR)));
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
