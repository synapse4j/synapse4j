package io.github.synapse4j.json;

import static io.github.synapse4j.json.JsonSchemaKeywords.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import lombok.EqualsAndHashCode;

/**
 * A {@link JsonSchema} in its object form, immutable.
 *
 * <p>
 * Every keyword a node carries lives in one map, keyed by its JSON name and holding the JSON data it
 * is. The keywords the interface names have a getter here; every other keyword is read with
 * {@link #get(String)}. {@link #keys()} enumerates what a node carries, modelled or not, so a keyword
 * this class does not know survives a round trip untouched.
 *
 * <p>
 * A keyword is carried exactly when it was set, {@code null} and all, so a keyword whose JSON value is
 * null (such as {@code "const": null}) survives. Reading never sets anything: a getter answers
 * {@code null} for a keyword that was not set and leaves the node as it was.
 *
 * <p>
 * The map and every collection below it are frozen, so nothing a getter hands out can change the
 * schema. A node is assembled through {@link JsonSchemaBuilder} and read from then on; a change is a
 * new schema, built from the old one.
 *
 * <p>
 * A keyword whose value is a sub-schema is kept as a {@link JsonSchema} (or a list or a map of them),
 * so {@link #visit(Consumer)} and {@link #map(UnaryOperator)} reach every sub-schema. Every other value
 * is raw JSON data.
 *
 * <p>
 * The document shape — the maps, lists and scalars a JSON document is made of — is not this class's
 * business: {@link JsonSchemas} reads one into a schema and writes one back.
 *
 * <p>
 * The class is open, not final: a provider or an application may extend it. The constructor freezes
 * what it is given, so a subclass inherits the immutable contract.
 */
@EqualsAndHashCode
public class ObjectJsonSchema implements JsonSchema {

    /** Every keyword a node carries, keyed by JSON name: frozen, never changed after construction. */
    private final Map<String, Object> values;

    /**
     * Creates the object form from its keywords, freezing the map and every collection below it so
     * nothing a getter hands out can change the schema.
     *
     * @param values the keywords; must not be {@code null}
     */
    protected ObjectJsonSchema(Map<String, Object> values) {
        this.values = freeze(values);
    }

    @Override
    public @Nullable Boolean asBoolean() {
        return null;
    }

    /**
     * The JSON types. A document spells one type as a string and several as an array; both read as a
     * list here, and a node that says nothing about its type answers {@code null}.
     */
    @Override
    public @Nullable List<String> getType() {
        Object type = values.get(TYPE);
        if (type instanceof String single) {
            return List.of(single);
        }
        return getList(TYPE, String.class);
    }

    @Override
    public @Nullable String getTitle() {
        return get(TITLE, String.class);
    }

    @Override
    public @Nullable String getDescription() {
        return get(DESCRIPTION, String.class);
    }

    @Override
    public @Nullable Map<String, JsonSchema> getProperties() {
        return getMap(PROPERTIES, JsonSchema.class);
    }

    @Override
    public @Nullable List<String> getRequired() {
        return getList(REQUIRED, String.class);
    }

    @Override
    public @Nullable JsonSchema getAdditionalProperties() {
        return get(ADDITIONAL_PROPERTIES, JsonSchema.class);
    }

    @Override
    public @Nullable String getRef() {
        return get(REF, String.class);
    }

    @Override
    public @Nullable Map<String, JsonSchema> getDefs() {
        return getMap(DEFS, JsonSchema.class);
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    @Override
    public @Nullable Object get(String keyword) {
        return values.get(keyword);
    }

    @Override
    public <T> @Nullable T get(String keyword, Class<T> type) {
        Object value = values.get(keyword);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable List<T> getList(String keyword, Class<T> type) {
        Object value = values.get(keyword);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        for (Object element : list) {
            if (!type.isInstance(element)) {
                return null;
            }
        }
        return (List<T>) list;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable Map<String, T> getMap(String keyword, Class<T> type) {
        Object value = values.get(keyword);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String) || !type.isInstance(entry.getValue())) {
                return null;
            }
        }
        return (Map<String, T>) map;
    }

    @Override
    public void visit(Consumer<JsonSchema> visitor) {
        visitor.accept(this);
        for (JsonSchema subSchema : subSchemas()) {
            subSchema.visit(visitor);
        }
    }

    @Override
    public JsonSchema map(UnaryOperator<JsonSchema> fn) {
        JsonSchema result = this;
        Map<String, Object> changed = null;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object mapped = mapValue(entry.getValue(), fn);
            if (mapped != entry.getValue()) {
                if (changed == null) {
                    changed = new LinkedHashMap<>(values);
                }
                changed.put(entry.getKey(), mapped);
            }
        }
        if (changed != null) {
            result = new ObjectJsonSchema(changed);
        }
        return fn.apply(result);
    }

    @Override
    public String toString() {
        return values.toString();
    }

    private List<JsonSchema> subSchemas() {
        List<JsonSchema> subSchemas = new ArrayList<>();
        values.values().forEach(value -> collect(value, subSchemas));
        return subSchemas;
    }

    private static void collect(@Nullable Object value, List<JsonSchema> target) {
        if (value instanceof JsonSchema schema) {
            target.add(schema);
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(element -> collect(element, target));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(element -> collect(element, target));
        }
    }

    private static @Nullable Object mapValue(@Nullable Object value, UnaryOperator<JsonSchema> fn) {
        if (value instanceof JsonSchema schema) {
            return schema.map(fn);
        }
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                @Nullable
                Object mappedElement = mapValue(element, fn);
                mapped.add(mappedElement);
                changed |= mappedElement != element;
            }
            return changed ? mapped : value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                @Nullable
                Object mappedElement = mapValue(entry.getValue(), fn);
                mapped.put(String.valueOf(entry.getKey()), mappedElement);
                changed |= mappedElement != entry.getValue();
            }
            return changed ? mapped : value;
        }
        return value;
    }

    /**
     * Freezes a node's keywords: the map and every collection below it become unmodifiable, so the
     * schema cannot be changed through a getter. A sub-schema is left as it is — it is immutable
     * already — so only the raw containers are copied.
     *
     * @param values the keywords to freeze; must not be {@code null}
     * @return the frozen keywords; never {@code null}
     */
    private static Map<String, Object> freeze(Map<String, Object> values) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        values.forEach((keyword, value) -> frozen.put(keyword, freezeValue(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static @Nullable Object freezeValue(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            map.forEach((name, element) -> frozen.put(name, freezeValue(element)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            list.forEach(element -> frozen.add(freezeValue(element)));
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }

}
