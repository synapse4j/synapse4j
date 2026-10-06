package io.github.synapse4j.json;

/**
 * A step that adjusts a {@link JsonSchema}: what needs changing, changed; the rest, left alone.
 *
 * <p>
 * A schema describes a type; what a particular protocol accepts may be narrower, or spelled
 * differently. This is the seam where the difference is closed — a step trims what the target does
 * not honour, or respells what it spells differently, so what leaves is what the target takes. Where
 * the steps run, and how many there are, is the caller's business; one instance is one step.
 *
 * <p>
 * A schema is a value, so a step answers the schema to use rather than changing the one it was
 * handed; a step that changed nothing may answer the one it was handed, sparing a copy. The answer is
 * what the next step is handed, and what the caller ends up with. A node handed in may be the boolean
 * form as well as the object form.
 */
@FunctionalInterface
public interface JsonSchemaCustomizer {

    /**
     * Adjusts one schema.
     *
     * @param schema the schema as it reached this step; never {@code null}
     * @return the schema to use; never {@code null}
     */
    JsonSchema customize(JsonSchema schema);

}
