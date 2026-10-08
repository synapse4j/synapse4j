package io.github.synapse4j.json;

import static io.github.synapse4j.json.JsonSchemaKeywords.*;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The forms the value of a {@link JsonSchema} keyword may take, and which keywords carry sub-schemas.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonSchemas {

    /**
     * A form the value of a keyword takes: a sub-schema, a list of them, or a map of them by name.
     *
     * <p>
     * An enum because JSON Schema fixes exactly these three and nothing extends them: a keyword's value
     * is a schema, an array of schemas, or an object of them keyed by name. Which keywords carry one is
     * a list of its own, and grows without {@link #shapesOf(String)} changing.
     */
    public enum Shape {

        /** A single sub-schema. */
        SCHEMA,

        /** A list of sub-schemas. */
        SCHEMA_LIST,

        /** Sub-schemas by name. */
        SCHEMA_MAP

    }

    /**
     * The keywords this library reads as carrying sub-schemas, and the forms their value may take.
     * This is what {@link #shapesOf(String)} answers from.
     *
     * <p>
     * The table is written out rather than derived, because a value does not say on its own whether it
     * is a sub-schema: {@code true} as the value of {@code additionalProperties} is a boolean schema,
     * while {@code true} as the value of {@code uniqueItems} is a boolean — only the keyword tells them
     * apart. Which keywords carry sub-schemas is the specification's to say, not something to infer
     * from a value, so the library carries the knowledge, and the table grows when the specification
     * does. Every schema-valued keyword is here, grouped by the newest draft that still supports it, so
     * a walk reaches every sub-schema whatever dialect a schema is written in.
     *
     * <p>
     * One draft-07 keyword is left out on purpose: {@code dependencies}. Its value is a schema or a list
     * of names, and each entry in its object form is too, so no one form describes it. It is carried as
     * raw data instead — nothing is lost, but a sub-schema under it is not walked.
     */
    private static final Map<String, Set<Shape>> SCHEMA_KEYWORDS = Map.ofEntries(
            // still in the latest spec, 2020-12
            Map.entry(PROPERTIES, Set.of(Shape.SCHEMA_MAP)),
            Map.entry(PATTERN_PROPERTIES, Set.of(Shape.SCHEMA_MAP)),
            Map.entry(ADDITIONAL_PROPERTIES, Set.of(Shape.SCHEMA)),
            Map.entry(ITEMS, Set.of(Shape.SCHEMA, Shape.SCHEMA_LIST)),
            Map.entry(PROPERTY_NAMES, Set.of(Shape.SCHEMA)),
            Map.entry(CONTAINS, Set.of(Shape.SCHEMA)),
            Map.entry(IF, Set.of(Shape.SCHEMA)),
            Map.entry(THEN, Set.of(Shape.SCHEMA)),
            Map.entry(ELSE, Set.of(Shape.SCHEMA)),
            Map.entry(DEFS, Set.of(Shape.SCHEMA_MAP)),
            Map.entry(DEPENDENT_SCHEMAS, Set.of(Shape.SCHEMA_MAP)),
            Map.entry(UNEVALUATED_ITEMS, Set.of(Shape.SCHEMA)),
            Map.entry(UNEVALUATED_PROPERTIES, Set.of(Shape.SCHEMA)),
            Map.entry(CONTENT_SCHEMA, Set.of(Shape.SCHEMA)),
            Map.entry(PREFIX_ITEMS, Set.of(Shape.SCHEMA_LIST)),
            Map.entry(ALL_OF, Set.of(Shape.SCHEMA_LIST)),
            Map.entry(ANY_OF, Set.of(Shape.SCHEMA_LIST)),
            Map.entry(ONE_OF, Set.of(Shape.SCHEMA_LIST)),
            Map.entry(NOT, Set.of(Shape.SCHEMA)),

            // dropped in 2020-12; last in 2019-09
            Map.entry(ADDITIONAL_ITEMS, Set.of(Shape.SCHEMA)),

            // dropped in 2019-09; last in draft-07
            Map.entry(DEFINITIONS, Set.of(Shape.SCHEMA_MAP)));

    /**
     * The forms this library knows the value of the given keyword may take, or {@code null} when the
     * library has not modelled the keyword at all.
     *
     * <p>
     * Only the keywords whose value carries sub-schemas are modelled: this library is built for
     * talking to language models, not for validating JSON Schema in full, so it types the subset it
     * needs rather than every keyword the specification gives a type. A {@code null} answer says the
     * library has no knowledge of the keyword — not that its value is untyped, since the
     * specification types every one. The set grows as more keywords are modelled, without this method
     * changing.
     *
     * @param keyword the keyword name
     * @return the forms, or {@code null} when the keyword is not modelled
     */
    public static @Nullable Set<Shape> shapesOf(String keyword) {
        return SCHEMA_KEYWORDS.get(keyword);
    }

}
