package io.github.synapse4j.json;

import static io.github.synapse4j.json.JsonSchemaKeywords.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.exception.SynapseException;
import lombok.NonNull;

/**
 * Builds a {@link JsonSchema} in its object form.
 *
 * <p>
 * Keywords are accumulated here, keyed by their JSON name, and frozen into an immutable
 * {@link ObjectJsonSchema} by {@link #build()}. The keywords the interface names have a setter; every
 * other keyword is written with {@link #put(String, Object)}. {@link JsonSchema#keys()} on the built
 * schema enumerates what a node carries, modelled or not, so a keyword this class does not know
 * survives.
 *
 * <p>
 * A keyword is carried exactly when it was set, {@code null} and all, so a keyword whose JSON value is
 * null (such as {@code "const": null}) survives. A modelled keyword is cleared by setting it to
 * {@code null}; {@link #put(String, Object)} keeps what it is given.
 *
 * <p>
 * A keyword whose value is a sub-schema is kept as a {@link JsonSchema} (or a list or a map of them),
 * so the built schema's {@link JsonSchema#visit} and {@link JsonSchema#map} reach every sub-schema.
 * Every other value is raw JSON data.
 *
 * <p>
 * An instance is not thread-safe while it is being assembled. Once {@link #build()} has run, the built
 * schema is immutable and shareable.
 */
public class JsonSchemaBuilder {

    /** Every keyword being assembled, keyed by JSON name: the one place the data lives. */
    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Starts a builder carrying the keywords of an existing object-form schema. The copy is shallow: a
     * keyword whose value is a sub-schema is carried by reference, so the builder and {@code schema}
     * share those nodes, which are immutable already.
     *
     * <p>
     * A boolean schema is refused: it carries no keyword, and there is no object form to build from it.
     *
     * @param schema the schema to start from; must not be {@code null}
     * @return a builder carrying the schema's keywords; never {@code null}
     * @throws SynapseException if {@code schema} is the boolean form
     */
    public static JsonSchemaBuilder from(@NonNull JsonSchema schema) {
        if (schema.asBoolean() != null) {
            throw new SynapseException("a boolean schema has no object form to build from: " + schema);
        }
        JsonSchemaBuilder builder = new JsonSchemaBuilder();
        for (String keyword : schema.keys()) {
            builder.values.put(keyword, schema.get(keyword));
        }
        return builder;
    }

    /**
     * Sets the JSON types, replacing whatever the schema held before. Set here, they are written as
     * the array a document spells several types with; {@link #setType(String)} writes a single type as
     * a string.
     *
     * @param type the types; must not be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder setType(@NonNull List<String> type) {
        values.put(TYPE, type);
        return this;
    }

    /**
     * Sets a single JSON type, replacing whatever the schema held before. It is written as the string
     * a document spells one type with; {@link #setType(List)} writes the array form.
     *
     * @param type one type, for example {@code object}; must not be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder setType(@NonNull String type) {
        values.put(TYPE, type);
        return this;
    }

    /**
     * Sets the title, clearing whatever the schema held before when the title is {@code null}.
     *
     * @param title the title; may be {@code null} to clear
     * @return this builder
     */
    public JsonSchemaBuilder setTitle(@Nullable String title) {
        putOrRemove(TITLE, title);
        return this;
    }

    /**
     * Sets the description, clearing whatever the schema held before when the description is
     * {@code null}.
     *
     * @param description the description; may be {@code null} to clear
     * @return this builder
     */
    public JsonSchemaBuilder setDescription(@Nullable String description) {
        putOrRemove(DESCRIPTION, description);
        return this;
    }

    /**
     * Sets the properties, replacing whatever the schema held before.
     *
     * @param properties the properties; must not be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder setProperties(@NonNull Map<String, JsonSchema> properties) {
        values.put(PROPERTIES, properties);
        return this;
    }

    /**
     * Sets the required names, replacing whatever the schema held before.
     *
     * @param required the names; must not be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder setRequired(@NonNull List<String> required) {
        values.put(REQUIRED, required);
        return this;
    }

    /**
     * Sets the schema of the array's elements, clearing whatever the schema held before when the
     * schema is {@code null}.
     *
     * @param items the schema; may be {@code null} to clear
     * @return this builder
     */
    public JsonSchemaBuilder setItems(@Nullable JsonSchema items) {
        putOrRemove(ITEMS, items);
        return this;
    }

    /**
     * Sets what the object does about properties beyond its own, clearing whatever the schema held
     * before when the schema is {@code null}.
     *
     * @param additionalProperties the schema; may be {@code null} to clear
     * @return this builder
     */
    public JsonSchemaBuilder setAdditionalProperties(@Nullable JsonSchema additionalProperties) {
        putOrRemove(ADDITIONAL_PROPERTIES, additionalProperties);
        return this;
    }

    /**
     * Sets the reference, clearing whatever the schema held before when the reference is {@code null}.
     *
     * @param ref the reference; may be {@code null} to clear
     * @return this builder
     */
    public JsonSchemaBuilder setRef(@Nullable String ref) {
        putOrRemove(REF, ref);
        return this;
    }

    /**
     * Sets the reusable sub-schemas, replacing whatever the schema held before.
     *
     * @param defs the definitions; must not be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder setDefs(@NonNull Map<String, JsonSchema> defs) {
        values.put(DEFS, defs);
        return this;
    }

    /**
     * Sets a keyword, replacing any value carried under that name before. This is how a keyword the
     * class does not model is written; a modelled one belongs in its setter. What is given is kept as
     * it is — {@code null} included, so a keyword whose JSON value is null is expressible here.
     *
     * @param keyword the keyword; must not be {@code null}
     * @param value   the value; stored as-is, may be {@code null}
     * @return this builder
     */
    public JsonSchemaBuilder put(@NonNull String keyword, @Nullable Object value) {
        values.put(keyword, value);
        return this;
    }

    /**
     * Freezes what was assembled into an immutable schema.
     *
     * @return the schema; never {@code null}
     */
    public ObjectJsonSchema build() {
        return new ObjectJsonSchema(values);
    }

    private void putOrRemove(String keyword, @Nullable Object value) {
        if (value == null) {
            values.remove(keyword);
        } else {
            values.put(keyword, value);
        }
    }

}
