package io.github.synapse4j.json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.synapse4j.exception.SynapseException;
import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Operations on a {@link JsonSchema}: the conversions between a schema and the JSON data a document
 * spells it with, and the rewrites that produce another schema rather than change the one given.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonSchemas {

    /** JSON names of the keywords this class reads, writes and walks. */
    private static final String PROPERTIES = "properties";
    private static final String PATTERN_PROPERTIES = "patternProperties";
    private static final String DEFS = "$defs";
    private static final String DEFINITIONS = "definitions";
    private static final String DEPENDENT_SCHEMAS = "dependentSchemas";
    private static final String ITEMS = "items";
    private static final String ADDITIONAL_ITEMS = "additionalItems";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String NOT = "not";
    private static final String IF = "if";
    private static final String THEN = "then";
    private static final String ELSE = "else";
    private static final String CONTAINS = "contains";
    private static final String PROPERTY_NAMES = "propertyNames";
    private static final String UNEVALUATED_ITEMS = "unevaluatedItems";
    private static final String UNEVALUATED_PROPERTIES = "unevaluatedProperties";
    private static final String CONTENT_SCHEMA = "contentSchema";
    private static final String ALL_OF = "allOf";
    private static final String ANY_OF = "anyOf";
    private static final String ONE_OF = "oneOf";
    private static final String PREFIX_ITEMS = "prefixItems";

    /** The reference keyword, and the prefix a reference into {@code $defs} is spelled with. */
    private static final String REF = "$ref";
    private static final String DEFS_PREFIX = "#/$defs/";

    /** The shape of a schema-valued keyword's value. */
    private enum Shape {
        SCHEMA, SCHEMA_LIST, SCHEMA_MAP
    }

    /**
     * Every keyword whose value is a schema, a list of schemas or a map of them, and which shape it
     * has. This is what tells {@link #fromDocument(Object)} how to read a document.
     */
    private static final Map<String, Shape> SCHEMA_KEYWORDS = Map.ofEntries(
            Map.entry(PROPERTIES, Shape.SCHEMA_MAP),
            Map.entry(PATTERN_PROPERTIES, Shape.SCHEMA_MAP),
            Map.entry(DEFS, Shape.SCHEMA_MAP),
            Map.entry(DEFINITIONS, Shape.SCHEMA_MAP),
            Map.entry(DEPENDENT_SCHEMAS, Shape.SCHEMA_MAP),
            Map.entry(ITEMS, Shape.SCHEMA),
            Map.entry(ADDITIONAL_ITEMS, Shape.SCHEMA),
            Map.entry(ADDITIONAL_PROPERTIES, Shape.SCHEMA),
            Map.entry(NOT, Shape.SCHEMA),
            Map.entry(IF, Shape.SCHEMA),
            Map.entry(THEN, Shape.SCHEMA),
            Map.entry(ELSE, Shape.SCHEMA),
            Map.entry(CONTAINS, Shape.SCHEMA),
            Map.entry(PROPERTY_NAMES, Shape.SCHEMA),
            Map.entry(UNEVALUATED_ITEMS, Shape.SCHEMA),
            Map.entry(UNEVALUATED_PROPERTIES, Shape.SCHEMA),
            Map.entry(CONTENT_SCHEMA, Shape.SCHEMA),
            Map.entry(ALL_OF, Shape.SCHEMA_LIST),
            Map.entry(ANY_OF, Shape.SCHEMA_LIST),
            Map.entry(ONE_OF, Shape.SCHEMA_LIST),
            Map.entry(PREFIX_ITEMS, Shape.SCHEMA_LIST));

    /**
     * Returns a copy of {@code schema} with its {@code $ref}s resolved.
     *
     * <p>
     * Every reference into {@code $defs} is replaced by a copy of the definition it names and
     * {@code #} by the root schema, so the answer reads without {@code $defs}. A reference that closes
     * a cycle — one whose target is a schema already on the path down to it — is left as it stands, and
     * the definition it names stays in {@code $defs}: a schema that contains itself has no document to
     * expand into. A reference that names nothing is left as it stands; this class does not invent a
     * schema. What remains of {@code $defs} is exactly what a kept reference still names, so a
     * definition that was fully inlined is dropped.
     *
     * <p>
     * The argument is untouched: the answer is a new schema. This is the opposite of
     * {@link JsonSchema#visit}, which changes the schema it walks.
     *
     * @param schema the schema to inline; must not be {@code null}
     * @return a copy with its references resolved; never {@code null}
     */
    public static JsonSchema inline(JsonSchema schema) {
        Map<String, Object> root = asMap((Map<?, ?>) toDocument(schema));
        Map<String, Object> inlined = inlineSchema(root, root, Collections.newSetFromMap(new IdentityHashMap<>()));
        pruneDefs(inlined);
        return fromDocument(inlined);
    }

    /**
     * Inlines one schema document. {@code path} holds the schemas currently being expanded, by
     * identity, so a reference back to one of them is recognised as a cycle.
     */
    private static Map<String, Object> inlineSchema(
            Map<String, Object> node, Map<String, Object> root, Set<Map<String, Object>> path) {
        boolean added = path.add(node);
        try {
            Object ref = node.get(REF);
            if (ref instanceof String reference) {
                Map<String, Object> target = resolve(reference, root);
                if (target != null && !path.contains(target)) {
                    Map<String, Object> inlined = inlineSchema(target, root, path);
                    inlined.putAll(inlineBody(node, root, path, false));
                    return inlined;
                }
            }
            return inlineBody(node, root, path, true);
        } finally {
            if (added) {
                path.remove(node);
            }
        }
    }

    /**
     * Copies a schema document's keywords, resolving the sub-schemas among them. {@code $ref} is kept
     * only when the caller could not resolve it; otherwise it has already been replaced by the
     * definition it named.
     */
    private static Map<String, Object> inlineBody(
            Map<String, Object> node, Map<String, Object> root, Set<Map<String, Object>> path, boolean keepRef) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String keyword = entry.getKey();
            Object value = entry.getValue();
            if (REF.equals(keyword)) {
                if (keepRef) {
                    result.put(keyword, value);
                }
                continue;
            }
            result.put(keyword, inlineValue(keyword, value, root, path));
        }
        return result;
    }

    private static Object inlineValue(
            String keyword, Object value, Map<String, Object> root, Set<Map<String, Object>> path) {
        if ((PROPERTIES.equals(keyword) || DEFS.equals(keyword)) && value instanceof Map<?, ?> schemas) {
            return inlineSchemas(schemas, root, path);
        }
        if ((ITEMS.equals(keyword) || ADDITIONAL_PROPERTIES.equals(keyword)) && value instanceof Map<?, ?> schema) {
            return inlineSchema(asMap(schema), root, path);
        }
        if ((ANY_OF.equals(keyword) || ONE_OF.equals(keyword) || ALL_OF.equals(keyword))
                && value instanceof List<?> list) {
            return inlineList(list, root, path);
        }
        return value;
    }

    private static Map<String, Object> inlineSchemas(
            Map<?, ?> schemas, Map<String, Object> root, Set<Map<String, Object>> path) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : schemas.entrySet()) {
            Object nested = entry.getValue();
            result.put(String.valueOf(entry.getKey()),
                    nested instanceof Map<?, ?> schema ? inlineSchema(asMap(schema), root, path) : nested);
        }
        return result;
    }

    private static List<Object> inlineList(List<?> list, Map<String, Object> root, Set<Map<String, Object>> path) {
        List<Object> result = new ArrayList<>();
        for (Object element : list) {
            result.add(element instanceof Map<?, ?> schema ? inlineSchema(asMap(schema), root, path) : element);
        }
        return result;
    }

    /**
     * Looks a reference up against the root document: {@code #} is the root itself and
     * {@code #/$defs/Name} is the definition of that name. Anything else names nothing here, which
     * includes a name {@code $defs} does not hold.
     */
    private static @Nullable Map<String, Object> resolve(String ref, Map<String, Object> root) {
        if (ref.equals("#")) {
            return root;
        }
        String name = definitionName(ref);
        if (name == null) {
            return null;
        }
        Object defs = root.get(DEFS);
        if (!(defs instanceof Map<?, ?> definitions)) {
            return null;
        }
        Object definition = definitions.get(name);
        return definition instanceof Map<?, ?> schema ? asMap(schema) : null;
    }

    /**
     * Drops the definitions no kept reference reaches, so a definition that was inlined everywhere is
     * gone from the answer. A definition a kept one still references is reached through that one, so
     * the walk follows references out of the definitions it keeps.
     */
    private static void pruneDefs(Map<String, Object> result) {
        Object defsValue = result.get(DEFS);
        if (!(defsValue instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> defs = asMap((Map<?, ?>) defsValue);

        Set<String> kept = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> refs = new LinkedHashSet<>();
        collectRefs(result, DEFS, refs);
        for (String ref : refs) {
            String name = definitionName(ref);
            if (name != null && kept.add(name)) {
                pending.add(name);
            }
        }
        while (!pending.isEmpty()) {
            Object definition = defs.get(pending.poll());
            if (definition == null) {
                continue;
            }
            Set<String> nested = new LinkedHashSet<>();
            collectRefs(definition, null, nested);
            for (String ref : nested) {
                String name = definitionName(ref);
                if (name != null && kept.add(name)) {
                    pending.add(name);
                }
            }
        }

        defs.keySet().retainAll(kept);
        if (defs.isEmpty()) {
            result.remove(DEFS);
        }
    }

    /** Collects every {@code $ref} string below {@code node}, skipping the named key at its own level. */
    private static void collectRefs(@Nullable Object node, @Nullable String skip, Set<String> refs) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String keyword = String.valueOf(entry.getKey());
                if (keyword.equals(skip)) {
                    continue;
                }
                if (REF.equals(keyword) && entry.getValue() instanceof String reference) {
                    refs.add(reference);
                } else {
                    collectRefs(entry.getValue(), null, refs);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object element : list) {
                collectRefs(element, null, refs);
            }
        }
    }

    private static @Nullable String definitionName(String ref) {
        return ref.startsWith(DEFS_PREFIX) ? ref.substring(DEFS_PREFIX.length()) : null;
    }

    /**
     * Writes a schema as the JSON value a document spells it with: {@code true} or {@code false} for
     * the boolean form, an object for the object form, its sub-schemas written the same way.
     *
     * <p>
     * A schema reached through two different paths is written out at each of them, JSON having no way
     * to share one. One that contains itself has no document at all, and is refused.
     *
     * @param schema the schema to write; must not be {@code null}
     * @return the schema as JSON data — a {@link Boolean} or a {@link Map}; never {@code null}
     * @throws SynapseException if the schema contains itself
     */
    public static Object toDocument(JsonSchema schema) {
        return document(schema, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object document(JsonSchema schema, Set<JsonSchema> path) {
        Boolean asBoolean = schema.asBoolean();
        if (asBoolean != null) {
            return asBoolean;
        }
        if (!path.add(schema)) {
            throw cycle();
        }
        Map<String, Object> document = new LinkedHashMap<>();
        for (String keyword : schema.keys()) {
            document.put(keyword, document(schema.get(keyword), path));
        }
        path.remove(schema);
        return document;
    }

    private static @Nullable Object document(@Nullable Object value, Set<JsonSchema> path) {
        if (value instanceof JsonSchema schema) {
            return document(schema, path);
        }
        if (value instanceof List<?> list) {
            List<Object> documents = new ArrayList<>(list.size());
            list.forEach(element -> documents.add(document(element, path)));
            return documents;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> documents = new LinkedHashMap<>();
            map.forEach((name, element) -> documents.put(String.valueOf(name), document(element, path)));
            return documents;
        }
        return value;
    }

    /**
     * Reads a schema from the JSON value a document spells it with: {@code true} or {@code false} for
     * the boolean form, an object for the object form.
     *
     * <p>
     * A keyword whose value is a sub-schema is read into its schema shape; a keyword that arrives in a
     * shape the spec does not give it is carried as it arrived. The document is taken as it is: a graph
     * that contains itself is not detected here, and one that nests itself would recurse until the
     * stack runs out — a document read from JSON cannot do that, JSON having no cycles.
     *
     * @param document the schema as JSON data — a {@link Boolean} or a {@link Map}; must not be
     *                     {@code null}
     * @return the schema; never {@code null}
     * @throws SynapseException if the value is neither
     */
    public static JsonSchema fromDocument(Object document) {
        if (document instanceof Boolean flag) {
            return flag ? BooleanJsonSchema.TRUE : BooleanJsonSchema.FALSE;
        }
        if (document instanceof Map<?, ?> map) {
            MutableJsonSchema schema = new MutableJsonSchema();
            map.forEach((name, value) -> {
                String keyword = String.valueOf(name);
                schema.put(keyword, fromValue(keyword, value));
            });
            return schema;
        }
        throw new SynapseException("a schema is an object or a boolean, not " + document);
    }

    /**
     * Reads one keyword's value into its shape. A value the shape does not fit is carried as it
     * arrived. A list is read as a list of schemas whether or not the keyword is modelled as one:
     * {@code items} is a single schema in 2020-12 and an array of them in draft-07.
     */
    private static @Nullable Object fromValue(String keyword, @Nullable Object value) {
        Shape shape = SCHEMA_KEYWORDS.get(keyword);
        if (shape == null) {
            return value;
        }
        if (shape == Shape.SCHEMA_MAP) {
            if (value instanceof Map<?, ?> map && map.values().stream().allMatch(JsonSchemas::isSchema)) {
                Map<String, JsonSchema> schemas = new LinkedHashMap<>();
                map.forEach((name, nested) -> schemas.put(String.valueOf(name), fromDocument(nested)));
                return schemas;
            }
            return value;
        }
        if (value instanceof List<?> list && list.stream().allMatch(JsonSchemas::isSchema)) {
            List<JsonSchema> schemas = new ArrayList<>();
            list.forEach(element -> schemas.add(fromDocument(element)));
            return schemas;
        }
        if (value != null && isSchema(value)) {
            return fromDocument(value);
        }
        return value;
    }

    private static boolean isSchema(@Nullable Object value) {
        return value instanceof Map<?, ?> || value instanceof Boolean;
    }

    private static SynapseException cycle() {
        return new SynapseException(
                "the schema contains itself: a recursive schema is spelled with $ref, and a schema graph that cycles has no JSON document");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

}
