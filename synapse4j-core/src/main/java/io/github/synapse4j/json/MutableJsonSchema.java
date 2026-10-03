package io.github.synapse4j.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.github.synapse4j.exception.SynapseException;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A {@link JsonSchema} in its object form, changeable in place.
 *
 * <p>
 * Every keyword a node carries lives in one map, keyed by its JSON name and holding the JSON data it
 * is. The keywords the interface names have a getter and a setter here; every other keyword is read
 * with {@link #get(String)} and set with {@link #put(String, Object)}. {@link #keys()} enumerates what
 * a node carries, modelled or not, so a keyword this class does not know survives a round trip
 * untouched.
 *
 * <p>
 * A keyword is carried exactly when it was set, {@code null} and all, so a keyword whose JSON value is
 * null (such as {@code "const": null}) survives. A modelled keyword is cleared by setting it to
 * {@code null}; {@link #put(String, Object)} keeps what it is given. Reading never sets anything: a
 * getter answers empty or {@code null} for a keyword that was not set and leaves the node as it was.
 * A getter hands out the collection the node holds, not a copy — read it, do not change it; a keyword
 * is replaced through its setter.
 *
 * <p>
 * A keyword whose value is a sub-schema is kept as a {@link JsonSchema} (or a list or a map of them),
 * so {@link #visit(Consumer)} and {@link #map(UnaryOperator)} reach every sub-schema. Every other value
 * is raw JSON data.
 *
 * <p>
 * The document shape — the maps, lists and scalars a JSON document is made of — is not this class's
 * business: {@link JsonSchemas} reads one into a schema and writes one back. {@link #toString()} renders
 * that shape.
 *
 * <p>
 * This is the mutable form the interface names: a node is assembled here, through the setters and
 * {@link #put(String, Object)}, and read afterwards. It is not thread-safe while it is being
 * assembled, and once it is shared it is treated as read-only, per {@link JsonSchema}'s contract.
 *
 * <p>
 * The class is open, not final: a provider or an application may extend it.
 */
public class MutableJsonSchema implements JsonSchema {

    /** JSON names of the keywords the interface names. */
    private static final String TYPE = "type";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ITEMS = "items";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String DEFS = "$defs";
    private static final String REF = "$ref";

    /** Every keyword a node carries, keyed by JSON name: the one place a node's data lives. */
    private final Map<String, Object> values = new LinkedHashMap<>();

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
        return get(TYPE, List.class);
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
        return get(PROPERTIES, Map.class);
    }

    @Override
    public @Nullable List<String> getRequired() {
        return get(REQUIRED, List.class);
    }

    @Override
    public @Nullable JsonSchema getItems() {
        return get(ITEMS, JsonSchema.class);
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
        return get(DEFS, Map.class);
    }

    /**
     * Sets the JSON types, replacing whatever {@link #getType()} held before. Set here, they are
     * written as the array a document spells several types with; {@link #setType(String)} writes a
     * single type as a string.
     *
     * @param type the types; must not be {@code null}
     */
    public void setType(@NonNull List<String> type) {
        values.put(TYPE, new ArrayList<>(type));
    }

    /**
     * Sets a single JSON type, replacing whatever {@link #getType()} held before. It is written as the
     * string a document spells one type with; {@link #setType(List)} writes the array form.
     *
     * @param type one type, for example {@code object}; must not be {@code null}
     */
    public void setType(@NonNull String type) {
        values.put(TYPE, type);
    }

    /**
     * Sets the title, clearing whatever {@link #getTitle()} held before when the title is
     * {@code null}.
     *
     * @param title the title; may be {@code null} to clear
     */
    public void setTitle(@Nullable String title) {
        putOrRemove(TITLE, title);
    }

    /**
     * Sets the description, clearing whatever {@link #getDescription()} held before when the
     * description is {@code null}.
     *
     * @param description the description; may be {@code null} to clear
     */
    public void setDescription(@Nullable String description) {
        putOrRemove(DESCRIPTION, description);
    }

    /**
     * Sets the properties, replacing whatever {@link #getProperties()} held before.
     *
     * @param properties the properties; must not be {@code null}
     */
    public void setProperties(@NonNull Map<String, JsonSchema> properties) {
        values.put(PROPERTIES, new LinkedHashMap<>(properties));
    }

    /**
     * Sets the required names, replacing whatever {@link #getRequired()} held before.
     *
     * @param required the names; must not be {@code null}
     */
    public void setRequired(@NonNull List<String> required) {
        values.put(REQUIRED, new ArrayList<>(required));
    }

    /**
     * Sets the schema of the array's elements, clearing whatever {@link #getItems()} held before when
     * the schema is {@code null}.
     *
     * @param items the schema; may be {@code null} to clear
     */
    public void setItems(@Nullable JsonSchema items) {
        putOrRemove(ITEMS, items);
    }

    /**
     * Sets what the object does about properties beyond its own, clearing whatever
     * {@link #getAdditionalProperties()} held before when the schema is {@code null}.
     *
     * @param additionalProperties the schema; may be {@code null} to clear
     */
    public void setAdditionalProperties(@Nullable JsonSchema additionalProperties) {
        putOrRemove(ADDITIONAL_PROPERTIES, additionalProperties);
    }

    /**
     * Sets the reference, clearing whatever {@link #getRef()} held before when the reference is
     * {@code null}.
     *
     * @param ref the reference; may be {@code null} to clear
     */
    public void setRef(@Nullable String ref) {
        putOrRemove(REF, ref);
    }

    /**
     * Sets the reusable sub-schemas, replacing whatever {@link #getDefs()} held before.
     *
     * @param defs the definitions; must not be {@code null}
     */
    public void setDefs(@NonNull Map<String, JsonSchema> defs) {
        values.put(DEFS, new LinkedHashMap<>(defs));
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
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(String keyword, Class<?> type) {
        Object value = values.get(keyword);
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * Sets a keyword, replacing any value carried under that name before. This is how a keyword the
     * class does not model is written; a modelled one belongs in its setter. What is given is kept as
     * it is — {@code null} included, so a keyword whose JSON value is null is expressible here.
     *
     * @param keyword the keyword; must not be {@code null}
     * @param value   the value; stored as-is, may be {@code null}
     * @return this schema
     */
    public MutableJsonSchema put(@NonNull String keyword, @Nullable Object value) {
        values.put(keyword, value);
        return this;
    }

    @Override
    public void visit(Consumer<JsonSchema> visitor) {
        visit(this, visitor, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void visit(JsonSchema schema, Consumer<JsonSchema> visitor, Set<JsonSchema> path) {
        if (!path.add(schema)) {
            throw cycle();
        }
        visitor.accept(schema);
        if (schema instanceof MutableJsonSchema mutable) {
            for (JsonSchema subSchema : mutable.subSchemas()) {
                visit(subSchema, visitor, path);
            }
        }
        path.remove(schema);
    }

    @Override
    public JsonSchema map(UnaryOperator<JsonSchema> fn) {
        return map(this, fn, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static JsonSchema map(JsonSchema schema, UnaryOperator<JsonSchema> fn, Set<JsonSchema> path) {
        if (!path.add(schema)) {
            throw cycle();
        }
        JsonSchema result = schema;
        if (schema instanceof MutableJsonSchema mutable) {
            MutableJsonSchema copy = new MutableJsonSchema();
            copy.values.putAll(mutable.values);
            boolean changed = false;
            for (Map.Entry<String, Object> entry : mutable.values.entrySet()) {
                Object mapped = mapValue(entry.getValue(), fn, path);
                if (mapped != entry.getValue()) {
                    copy.values.put(entry.getKey(), mapped);
                    changed = true;
                }
            }
            if (changed) {
                result = copy;
            }
        }
        path.remove(schema);
        return fn.apply(result);
    }

    /**
     * Renders this schema in the shape {@link JsonSchemas#toDocument(JsonSchema)} produces rather than
     * dumping its fields: a recursive structure reads better the way it is written out.
     *
     * @return the rendered schema
     */
    @Override
    public String toString() {
        return "MutableJsonSchema" + JsonSchemas.toDocument(this);
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

    private static @Nullable Object mapValue(@Nullable Object value, UnaryOperator<JsonSchema> fn,
            Set<JsonSchema> path) {
        if (value instanceof JsonSchema schema) {
            return map(schema, fn, path);
        }
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                @Nullable
                Object mappedElement = mapValue(element, fn, path);
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
                Object mappedElement = mapValue(entry.getValue(), fn, path);
                mapped.put(String.valueOf(entry.getKey()), mappedElement);
                changed |= mappedElement != entry.getValue();
            }
            return changed ? mapped : value;
        }
        return value;
    }

    private void putOrRemove(String keyword, @Nullable Object value) {
        if (value == null) {
            values.remove(keyword);
        } else {
            values.put(keyword, value);
        }
    }

    private static SynapseException cycle() {
        return new SynapseException(
                "the schema contains itself: a recursive schema is spelled with $ref, and a schema graph that cycles has no JSON document");
    }

}
