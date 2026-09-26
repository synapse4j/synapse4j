package io.github.synapse4j.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.synapse4j.data.ProviderExtras;
import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

/**
 * A JSON Schema: what shape a JSON document has to have.
 *
 * <p>
 * Only the keywords that decide <em>structure</em> are fields here — the ones that nest, hold
 * sub-schemas, or say what kind of value is allowed. Keywords that merely constrain a value —
 * {@code format}, {@code pattern}, numeric and length bounds, {@code uniqueItems} and the like —
 * stay in the open part below: they are leaf scalars that no traversal needs to look at, and their
 * support differs from engine to engine, which is exactly the kind of thing that should travel
 * untouched rather than be pinned down here.
 *
 * <p>
 * Providers disagree about which keywords they honour: some ignore {@code format} silently, some
 * collapse {@code oneOf} into {@code anyOf}, some support only a subset of {@code pattern}. That
 * disagreement belongs to whoever speaks to the provider — the provider module rewrites or drops what
 * its target cannot express — and never to this class.
 *
 * <p>
 * A value in the open part is JSON data — a map, a list or a scalar — because that is what arrives from
 * and goes back to a JSON document. A sub-schema is none of those, so nesting one is the caller's move:
 * put {@code subSchema.toMap()} in the open part. Such a schema travels, but {@link #subSchemas()} will
 * not visit it, since a map that came from a schema cannot be told apart from one that did not.
 *
 * <p>
 * Deliberately not modelled, and handled by the open part instead:
 * <ul>
 * <li>a boolean schema ({@code true} / {@code false}): a generator never produces one, and several
 * engines reject it outright;</li>
 * <li>{@code const}: JSON Schema allows {@code "const": null}, and a field of type {@link Object}
 * cannot tell that apart from "no const at all".</li>
 * </ul>
 *
 * <p>
 * {@link #toMap()} and {@link #fromMap(Map)} convert this structure to and from the plain
 * Map/List/scalar shape a JSON document has, which is how a codec writes it out and reads it back.
 * {@link #toString()} renders that same shape, since a dump of the fields of a recursive structure
 * helps nobody.
 *
 * <p>
 * The class is open, not final: a provider or an application may extend it, as it may extend any
 * other structure here.
 */
@Getter
@Setter
@NoArgsConstructor
public class JsonSchema {

    /** JSON names of the keywords this class models, used when converting to and from a map. */
    private static final String TYPE = "type";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ITEMS = "items";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String ENUM = "enum";
    private static final String DEFS = "$defs";
    private static final String REF = "$ref";
    private static final String ANY_OF = "anyOf";
    private static final String ONE_OF = "oneOf";
    private static final String ALL_OF = "allOf";

    /**
     * The JSON types the value may have; empty means any type. A single entry is written back as a
     * plain string rather than a one-element array.
     */
    @NonNull
    private List<String> type = new ArrayList<>();

    /** The properties of an object, each with its own schema. Empty means the object has none. */
    @NonNull
    private Map<String, JsonSchema> properties = new LinkedHashMap<>();

    /** Names of the properties that must be present. Empty means none are required. */
    @NonNull
    private List<String> required = new ArrayList<>();

    /** Shorthand for the schema of the array's elements. */
    private @Nullable JsonSchema items;

    /** Whether properties beyond {@link #properties} are allowed; {@code null} means the schema is silent. */
    private @Nullable Boolean additionalProperties;

    /** The allowed values. Empty means the value is not constrained to a set. */
    @NonNull
    private List<Object> enumValues = new ArrayList<>();

    /** Reusable sub-schemas, addressed by {@link #ref}. Empty means there are none. */
    @NonNull
    private Map<String, JsonSchema> defs = new LinkedHashMap<>();

    /** A reference to a sub-schema, such as {@code #/$defs/Location}. */
    private @Nullable String ref;

    /** Sub-schemas of which at least one has to match. Empty means this keyword is absent. */
    @NonNull
    private List<JsonSchema> anyOf = new ArrayList<>();

    /** Sub-schemas of which exactly one has to match. Empty means this keyword is absent. */
    @NonNull
    private List<JsonSchema> oneOf = new ArrayList<>();

    /** Sub-schemas all of which have to match. Empty means this keyword is absent. */
    @NonNull
    private List<JsonSchema> allOf = new ArrayList<>();

    /** Name of the schema, for readers of the document. */
    private @Nullable String title;

    /** What the value means, for the model to read. */
    private @Nullable String description;

    /** Keywords this class does not model, carried through as the JSON data they arrived as. */
    private final ProviderExtras extras = new ProviderExtras();

    /**
     * Sets the JSON types, replacing whatever {@code getType()} held before.
     *
     * <p>
     * Written out rather than left to Lombok: the single-type convenience below carries the same name,
     * and Lombok generates no setter at all once one with that name exists.
     *
     * @param type the types; must not be {@code null}
     */
    public void setType(@NonNull List<String> type) {
        this.type = type;
    }

    /**
     * Sets a single JSON type, replacing whatever {@code getType()} held before.
     *
     * <p>
     * A convenience: most schemas name exactly one type, and writing it as a one-element list reads
     * worse than the type it names.
     *
     * @param type one type, for example {@code object}; must not be {@code null}
     */
    public void setType(@NonNull String type) {
        setType(new ArrayList<>(List.of(type)));
    }

    /**
     * Walks this schema and every sub-schema below it, this one first.
     *
     * <p>
     * The visitor receives each schema and may change it in place; the sub-schemas of that schema are
     * visited afterwards. The walk runs on a snapshot of each level's children, so a sub-schema added
     * during the walk is not visited, and one removed during the walk is still walked through. That
     * also means the walk cannot be stopped early by removing the schema it is at.
     *
     * @param visitor what to do with each schema; must not be {@code null}
     */
    public void visit(@NonNull Consumer<JsonSchema> visitor) {
        visitor.accept(this);
        for (JsonSchema subSchema : subSchemas()) {
            subSchema.visit(visitor);
        }
    }

    /**
     * Returns every sub-schema directly below this one, so that a caller can walk the tree without
     * knowing which field belongs to which keyword.
     *
     * <p>
     * The order is not part of the contract, hence a collection rather than a list. A sub-schema
     * reached through {@code getExtras()} is not included: this class cannot tell which of those
     * values are schemas.
     *
     * @return the sub-schemas; never {@code null}
     */
    public Collection<JsonSchema> subSchemas() {
        List<JsonSchema> subSchemas = new ArrayList<>();
        subSchemas.addAll(properties.values());
        subSchemas.addAll(defs.values());
        subSchemas.addAll(anyOf);
        subSchemas.addAll(oneOf);
        subSchemas.addAll(allOf);
        if (items != null) {
            subSchemas.add(items);
        }
        return subSchemas;
    }

    /**
     * Writes this schema as the plain shape a JSON document has: maps, lists and scalars, nothing of
     * this library.
     *
     * <p>
     * A field that carries nothing is left out — an empty collection and a null both mean the keyword
     * is absent. A single {@code type} is written as a string rather than a one-element
     * array. The open part is written at the same level, one entry per keyword, its value as it
     * stands; an open entry whose name is a keyword this class models is ignored, so a field always
     * wins over the open part.
     *
     * @return the schema as a map; never {@code null}
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!type.isEmpty()) {
            map.put(TYPE, type.size() == 1 ? type.get(0) : new ArrayList<>(type));
        }
        if (title != null) {
            map.put(TITLE, title);
        }
        if (description != null) {
            map.put(DESCRIPTION, description);
        }
        if (!properties.isEmpty()) {
            map.put(PROPERTIES, nestedMaps(properties));
        }
        if (!required.isEmpty()) {
            map.put(REQUIRED, new ArrayList<>(required));
        }
        if (items != null) {
            map.put(ITEMS, items.toMap());
        }
        if (additionalProperties != null) {
            map.put(ADDITIONAL_PROPERTIES, additionalProperties);
        }
        if (!enumValues.isEmpty()) {
            map.put(ENUM, new ArrayList<>(enumValues));
        }
        if (!defs.isEmpty()) {
            map.put(DEFS, nestedMaps(defs));
        }
        if (ref != null) {
            map.put(REF, ref);
        }
        if (!anyOf.isEmpty()) {
            map.put(ANY_OF, nestedMaps(anyOf));
        }
        if (!oneOf.isEmpty()) {
            map.put(ONE_OF, nestedMaps(oneOf));
        }
        if (!allOf.isEmpty()) {
            map.put(ALL_OF, nestedMaps(allOf));
        }
        // The open part fills only what no field claimed: a modelled keyword always wins.
        extras.nestedMap().forEach(map::putIfAbsent);
        return map;
    }

    /**
     * Reads a schema from the plain shape a JSON document has.
     *
     * <p>
     * A keyword this class models goes into its field. Everything else is carried in the open part
     * unchanged — and so is a modelled keyword that arrives in a shape this class does not expect: a
     * {@code properties} that is not a map, an {@code items} that is a list of schemas, an
     * {@code additionalProperties} that is a schema itself.
     *
     * @param map the schema as a map; must not be {@code null}
     * @return the schema; never {@code null}
     */
    public static JsonSchema fromMap(@NonNull Map<String, Object> map) {
        JsonSchema schema = new JsonSchema();
        map.forEach(schema::read);
        return schema;
    }

    /**
     * Renders this schema in the shape {@link #toMap()} produces rather than dumping its fields: a
     * recursive structure reads better the way it is written out.
     *
     * @return the rendered schema
     */
    @Override
    public String toString() {
        return "JsonSchema" + toMap();
    }

    private void read(String keyword, @Nullable Object value) {
        switch (keyword) {
            case TYPE -> readType(keyword, value);
            case TITLE -> readText(keyword, value, this::setTitle);
            case DESCRIPTION -> readText(keyword, value, this::setDescription);
            case REF -> readText(keyword, value, this::setRef);
            case REQUIRED -> readTexts(keyword, value, this::setRequired);
            case ENUM -> readValues(keyword, value);
            case ADDITIONAL_PROPERTIES -> readAdditionalProperties(keyword, value);
            case PROPERTIES -> readSchemas(keyword, value, this::setProperties);
            case DEFS -> readSchemas(keyword, value, this::setDefs);
            case ITEMS -> readSchema(keyword, value, this::setItems);
            case ANY_OF -> readSchemaList(keyword, value, this::setAnyOf);
            case ONE_OF -> readSchemaList(keyword, value, this::setOneOf);
            case ALL_OF -> readSchemaList(keyword, value, this::setAllOf);
            default -> carry(keyword, value);
        }
    }

    private void readType(String keyword, @Nullable Object value) {
        if (value instanceof String single) {
            setType(single);
        } else if (value instanceof List<?> types && types.stream().allMatch(String.class::isInstance)) {
            setType(new ArrayList<String>(types.stream().map(String.class::cast).toList()));
        } else {
            carry(keyword, value);
        }
    }

    private void readText(String keyword, @Nullable Object value, Consumer<String> setter) {
        if (value instanceof String text) {
            setter.accept(text);
        } else {
            carry(keyword, value);
        }
    }

    private void readTexts(String keyword, @Nullable Object value, Consumer<List<String>> setter) {
        if (value instanceof List<?> texts && texts.stream().allMatch(String.class::isInstance)) {
            setter.accept(new ArrayList<>(texts.stream().map(String.class::cast).toList()));
        } else {
            carry(keyword, value);
        }
    }

    private void readValues(String keyword, @Nullable Object value) {
        if (value instanceof List<?> values) {
            setEnumValues(new ArrayList<>(values));
        } else {
            carry(keyword, value);
        }
    }

    private void readAdditionalProperties(String keyword, @Nullable Object value) {
        if (value instanceof Boolean allowed) {
            setAdditionalProperties(allowed);
        } else {
            carry(keyword, value);
        }
    }

    private void readSchemas(String keyword, @Nullable Object value, Consumer<Map<String, JsonSchema>> setter) {
        if (value instanceof Map<?, ?> map && map.values().stream().allMatch(Map.class::isInstance)) {
            Map<String, JsonSchema> schemas = new LinkedHashMap<>();
            map.forEach((name, nested) -> schemas.put(String.valueOf(name), fromMap(asMap(nested))));
            setter.accept(schemas);
        } else {
            carry(keyword, value);
        }
    }

    private void readSchema(String keyword, @Nullable Object value, Consumer<JsonSchema> setter) {
        if (value instanceof Map<?, ?> map) {
            setter.accept(fromMap(asMap(map)));
        } else {
            carry(keyword, value);
        }
    }

    private void readSchemaList(String keyword, @Nullable Object value, Consumer<List<JsonSchema>> setter) {
        if (value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            List<JsonSchema> schemas = new ArrayList<>();
            list.forEach(nested -> schemas.add(fromMap(asMap(nested))));
            setter.accept(schemas);
        } else {
            carry(keyword, value);
        }
    }

    private void carry(String keyword, @Nullable Object value) {
        extras.put(keyword, value);
    }

    private static Map<String, Object> nestedMaps(Map<String, JsonSchema> schemas) {
        Map<String, Object> maps = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> maps.put(name, schema.toMap()));
        return maps;
    }

    private static List<Object> nestedMaps(Collection<JsonSchema> schemas) {
        List<Object> maps = new ArrayList<>();
        schemas.forEach(schema -> maps.add(schema.toMap()));
        return maps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(@Nullable Object value) {
        return (Map<String, Object>) value;
    }

}
