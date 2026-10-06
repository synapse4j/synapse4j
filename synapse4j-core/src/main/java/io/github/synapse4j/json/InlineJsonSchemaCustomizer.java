package io.github.synapse4j.json;

import static io.github.synapse4j.json.JsonSchemaKeywords.*;

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

import org.jspecify.annotations.Nullable;

/**
 * A {@link JsonSchemaCustomizer} that resolves a schema's references: every {@code $ref} into
 * {@code $defs} is replaced by a copy of the definition it names and {@code #} by the root schema, so
 * the answer reads without {@code $defs}. This is the step for a target whose protocol does not take
 * {@code $ref}.
 *
 * <p>
 * A reference that closes a cycle — one whose target is a schema already on the path down to it — is
 * left as it stands, and the definition it names stays in {@code $defs}: a schema that contains itself
 * has no document to expand into. A reference that names nothing is left as it stands; this step does
 * not invent a schema. What remains of {@code $defs} is exactly what a kept reference still names, so a
 * definition that was fully inlined is dropped.
 *
 * <p>
 * The schema handed in is untouched: the answer is always a new schema, whether or not a reference was
 * found.
 */
public class InlineJsonSchemaCustomizer implements JsonSchemaCustomizer {

    @Override
    public JsonSchema customize(JsonSchema schema) {
        return pruneDefs(inlineSchema(schema, schema, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    /**
     * Inlines one schema. A boolean schema carries no sub-schema and no reference and is answered as it
     * is; an object-form schema is copied with its sub-schemas inlined. {@code path} holds the schemas
     * currently being expanded, by identity, so a reference back to one of them is recognised as a
     * cycle.
     */
    private static JsonSchema inlineSchema(JsonSchema node, JsonSchema root, Set<JsonSchema> path) {
        return node.asBoolean() == null ? inlineObject(node, root, path).build() : node;
    }

    /**
     * Inlines an object-form schema into a new builder. A reference that resolves is replaced by the
     * inlined definition it names, with this node's other keywords on top; otherwise the node is copied
     * as it is, its {@code $ref} kept.
     */
    private static JsonSchemaBuilder inlineObject(JsonSchema node, JsonSchema root, Set<JsonSchema> path) {
        path.add(node);
        try {
            String ref = node.getRef();
            if (ref != null) {
                JsonSchema target = resolve(ref, root);
                if (target != null && target.asBoolean() == null && !path.contains(target)) {
                    JsonSchemaBuilder inlined = inlineObject(target, root, path);
                    inlineKeywords(inlined, node, root, path, false);
                    return inlined;
                }
            }
            JsonSchemaBuilder copy = new JsonSchemaBuilder();
            inlineKeywords(copy, node, root, path, true);
            return copy;
        } finally {
            path.remove(node);
        }
    }

    /**
     * Copies a node's keywords into {@code target}, inlining the sub-schemas among them. {@code $ref} is
     * kept only when the caller could not resolve it; otherwise it has already been replaced by the
     * definition it named.
     */
    private static void inlineKeywords(
            JsonSchemaBuilder target, JsonSchema node, JsonSchema root, Set<JsonSchema> path, boolean keepRef) {
        for (String keyword : node.keys()) {
            if (REF.equals(keyword)) {
                if (keepRef) {
                    target.put(keyword, node.get(keyword));
                }
                continue;
            }
            target.put(keyword, inlineValue(node.get(keyword), root, path));
        }
    }

    /** Inlines a keyword's value: a sub-schema, or the sub-schemas in a list or a map of them. */
    private static @Nullable Object inlineValue(@Nullable Object value, JsonSchema root, Set<JsonSchema> path) {
        if (value instanceof JsonSchema schema) {
            return inlineSchema(schema, root, path);
        }
        if (value instanceof List<?> list) {
            List<Object> inlined = new ArrayList<>(list.size());
            for (Object element : list) {
                inlined.add(inlineValue(element, root, path));
            }
            return inlined;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> inlined = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                inlined.put(String.valueOf(entry.getKey()), inlineValue(entry.getValue(), root, path));
            }
            return inlined;
        }
        return value;
    }

    /**
     * Looks a reference up against the root schema: {@code #} is the root itself and
     * {@code #/$defs/Name} is the definition of that name. Anything else names nothing here, which
     * includes a name {@code $defs} does not hold.
     */
    private static @Nullable JsonSchema resolve(String ref, JsonSchema root) {
        if (ref.equals("#")) {
            return root;
        }
        String name = definitionName(ref);
        if (name == null) {
            return null;
        }
        Map<String, JsonSchema> defs = root.getDefs();
        return defs == null ? null : defs.get(name);
    }

    /**
     * Drops the definitions no kept reference reaches, so a definition that was inlined everywhere is
     * gone from the answer. A definition a kept one still references is reached through that one, so
     * the walk follows references out of the definitions it keeps.
     */
    private static JsonSchema pruneDefs(JsonSchema result) {
        Map<String, JsonSchema> defs = result.getDefs();
        if (defs == null) {
            return result;
        }
        Set<String> kept = reachableDefs(result, defs);
        if (kept.containsAll(defs.keySet())) {
            return result;
        }
        JsonSchemaBuilder pruned = new JsonSchemaBuilder();
        for (String keyword : result.keys()) {
            if (!DEFS.equals(keyword)) {
                pruned.put(keyword, result.get(keyword));
            }
        }
        if (!kept.isEmpty()) {
            Map<String, JsonSchema> remaining = new LinkedHashMap<>();
            defs.forEach((name, definition) -> {
                if (kept.contains(name)) {
                    remaining.put(name, definition);
                }
            });
            pruned.setDefs(remaining);
        }
        return pruned.build();
    }

    /**
     * The names of the definitions a kept reference reaches, starting from the references outside
     * {@code $defs} and following the references out of every definition that is itself reached.
     */
    private static Set<String> reachableDefs(JsonSchema result, Map<String, JsonSchema> defs) {
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
            JsonSchema definition = defs.get(pending.poll());
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
        return kept;
    }

    /** Collects every {@code $ref} string below {@code node}, skipping the named keyword at its own level. */
    private static void collectRefs(@Nullable Object node, @Nullable String skip, Set<String> refs) {
        if (node instanceof JsonSchema schema) {
            for (String keyword : schema.keys()) {
                if (keyword.equals(skip)) {
                    continue;
                }
                Object value = schema.get(keyword);
                if (REF.equals(keyword) && value instanceof String reference) {
                    refs.add(reference);
                } else {
                    collectRefs(value, null, refs);
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equals(skip)) {
                    continue;
                }
                collectRefs(entry.getValue(), null, refs);
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

}
