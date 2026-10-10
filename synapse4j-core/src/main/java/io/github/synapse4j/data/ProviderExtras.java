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
 * Provider-specific fields that belong to one modelled thing and are merged into the outgoing payload
 * when the request is sent.
 *
 * <p>
 * A value is addressed by a path of one or more segments, joined with {@code .}; a segment that
 * contains {@code .} or {@code \} escapes that character with {@code \}, so a segment holding a dot
 * is written {@code a\.b}. A segment becomes a key of the outgoing object exactly as written: the
 * caller spells the provider's own wire name, so nothing is renamed, case-converted or otherwise
 * adapted, and the only structure a merge adds is the nesting the path describes.
 *
 * <p>
 * What is stored is opaque and never inspected, so a value may be anything the configured JSON codec
 * serializes. The names inside such a value are whatever that codec produces, so a field the protocol
 * spells differently belongs in a map instead.
 *
 * <p>
 * An instance answers for the thing it sits on: an entry may set a member of any object that thing
 * contributes, at any depth, until it meets a thing that carries extras of its own — there the reach
 * ends. The outer entry may still set that member to stand in place of the whole thing, so that
 * thing's own extras are never written; it may not set one of that thing's members, which answers for
 * itself. Where two instances land on one node, the one applied later is the one that goes out.
 *
 * <p>
 * Instances are mutable, in the spirit of {@link Map}, not value objects: the caller owns one, fills
 * it, and freezes it where the model requires. They are not thread-safe.
 *
 * <p>
 * A value is kept by reference and never copied when stored, so changing one afterwards is visible to
 * whoever reads the instance back, serialization included; making the entries unchangeable does not
 * make the values so.
 *
 * <p>
 * It is a pass-through, not a translation layer: what arrives from a provider goes back to it under
 * the same name, and what another provider would make of it is the application's decision.
 */
@EqualsAndHashCode(of = "values")
public class ProviderExtras implements Effective<ProviderExtras> {

    private static final char SEPARATOR = '.';
    private static final char ESCAPE = '\\';

    /**
     * Flat storage, keyed by the assembled path. Handed out read-only through {@link #rawMap()};
     * read-only outright on a frozen instance.
     */
    private final Map<String, Object> values;

    /**
     * Whether this instance is frozen. Not part of equality: a frozen instance and a mutable one holding
     * the same entries are equal.
     */
    private final boolean frozen;

    /**
     * Creates an empty, mutable instance.
     */
    public ProviderExtras() {
        this.values = new LinkedHashMap<>();
        this.frozen = false;
    }

    /**
     * A mutable copy of the given instance, whether or not that instance is frozen: the entries are its own,
     * the values in it are the ones stored, by reference.
     */
    public ProviderExtras(ProviderExtras other) {
        this.values = new LinkedHashMap<>(other.values);
        this.frozen = false;
    }

    /**
     * The frozen form: the given entries wrapped read-only, so every mutator refuses on its own.
     */
    private ProviderExtras(Map<String, Object> frozenValues) {
        this.values = Collections.unmodifiableMap(frozenValues);
        this.frozen = true;
    }

    /**
     * Returns a frozen copy: a new instance whose entries cannot be changed, safe to share where a
     * mutable one is not.
     *
     * <p>
     * The copy is shallow. The entries are copied, so a path set on this instance afterwards is
     * absent from the copy; the values in it are the ones stored, by reference, so a value the
     * caller still holds and later changes is seen through the copy too. A value is arbitrary
     * application data, so a deep copy is not offered.
     *
     * <p>
     * Freezing an instance that is already frozen returns it as it is.
     *
     * @return a frozen copy; never {@code null}
     */
    public ProviderExtras freeze() {
        return frozen ? this : new ProviderExtras(new LinkedHashMap<>(values));
    }

    /**
     * A mutable copy, whether or not this instance is frozen.
     */
    @Override
    public ProviderExtras copy() {
        return new ProviderExtras(this);
    }

    /**
     * Returns whether this instance is frozen: a frozen instance refuses every mutator and is safe to share,
     * and freezing one again answers it as it is. A mutable instance is its opposite — the accumulating
     * form a caller fills and then hands to a model constructor to freeze.
     *
     * @return {@code true} if this instance is frozen
     */
    public boolean isFrozen() {
        return frozen;
    }

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
     * @return {@code true} if this instance is empty
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
     * @return this instance
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
     * @return this instance
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
     * @return this instance
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
     * view, or through {@link #getRaw(String)}, not through {@link #get(String...)}, which addresses
     * path segments and so spells the same tree differently.
     *
     * @param key   the assembled key; must not be {@code null}
     * @param value the value; stored as-is, may be {@code null}
     * @return this instance
     */
    public ProviderExtras putRaw(@NonNull String key, @Nullable Object value) {
        clearAround(key);
        values.put(key, value);
        return this;
    }

    /**
     * Returns the value set under a key the caller has already assembled — the reading counterpart of
     * {@link #putRaw(String, Object)}, addressing the keys {@link #rawMap()} hands out rather than
     * the path segments {@link #get(String...)} takes.
     *
     * @param key the assembled key; must not be {@code null}
     * @return the value, or {@code null} if nothing is set under that key
     */
    public @Nullable Object getRaw(@NonNull String key) {
        return values.get(key);
    }

    /**
     * Merges another instance into this one. Entries of the other instance win where the paths are equal or
     * overlap.
     *
     * @param other the instance to merge in; must not be {@code null}
     * @return this instance
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
     * Takes into this instance the entries {@code other} sets that this one does not: a path this instance
     * already sets, or one that sits above or below such a path, is left as it is. The mirror of
     * {@link #putAll(ProviderExtras)}, where the other instance's entries win.
     */
    @Override
    public void fillFrom(ProviderExtras other) {
        if (other == this) {
            return;
        }
        for (Map.Entry<String, Object> entry : other.values.entrySet()) {
            String key = entry.getKey();
            if (!values.containsKey(key) && values.keySet().stream().noneMatch(held -> nests(key, held))) {
                values.put(key, entry.getValue());
            }
        }
    }

    /** Whether one of the two paths names a position inside the other. */
    private static boolean nests(String one, String other) {
        return one.startsWith(other + SEPARATOR) || other.startsWith(one + SEPARATOR);
    }

    /**
     * A new mutable instance holding {@code base}'s entries with {@code overlay} merged over them —
     * overlay wins where the two set the same path, as {@link #putAll(ProviderExtras)} does. The
     * result is always a new, mutable instance and never one of the inputs, so a frozen input stays
     * frozen and the caller decides when to freeze the result; a {@code null} argument is an empty
     * instance.
     *
     * @param base    the entries to start from; {@code null} for none
     * @param overlay the entries that win where the two overlap; {@code null} for none
     * @return a new mutable instance; never {@code null}
     */
    public static ProviderExtras merged(@Nullable ProviderExtras base, @Nullable ProviderExtras overlay) {
        ProviderExtras merged = new ProviderExtras();
        if (base != null) {
            merged.putAll(base);
        }
        if (overlay != null) {
            merged.putAll(overlay);
        }
        return merged;
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
     * Returns this instance as a nested map, with one level per path segment.
     *
     * <p>
     * The result is a fresh, mutable structure owned by the caller; changing the structure does not
     * affect this instance. The values in it are the ones stored, by reference.
     *
     * @return the nested view; never {@code null}, empty if nothing is set
     */
    public Map<String, Object> nestedMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        mergeInto(root);
        return root;
    }

    /**
     * Merges this instance into a tree the caller already built. Each path finds its way down and sets
     * itself, replacing whatever stands in the way of it: a leaf blocking the way is discarded, a
     * container on the way is walked through, the position itself is overwritten.
     *
     * <p>
     * The instance wins everywhere it sets a path and nowhere else: a member it never sets keeps the value
     * already in the map. Paths {@code a} and {@code a.b} are distinct positions, so setting
     * {@code a} replaces that whole value, while setting {@code a.b} reaches into it and leaves its
     * other members alone.
     *
     * <p>
     * Two instances may be merged into one map, one after the other. Each is applied over what is
     * already there, so the one merged later is the one that goes out.
     *
     * @param members the tree to write into; the containers it already holds must be mutable
     * @return this instance
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
        return values.toString();
    }

    private ProviderExtras putPath(@Nullable Object value, @NonNull List<String> path) {
        String key = encode(path.toArray(new String[0]));
        clearAround(key);
        values.put(key, value);
        return this;
    }

    /**
     * Setting a path replaces that position in the tree the way a map's put replaces a key, rather
     * than being refused for overlapping what is already there.
     */
    private void clearAround(String key) {
        values.keySet().removeIf(held -> nests(key, held));
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
