package io.github.synapse4j.json;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The names of the JSON Schema keywords: the one place they live, so the classes that read, write and
 * walk a schema all spell them the same way — and an application that builds or inspects one can too.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonSchemaKeywords {

    public static final String TYPE = "type";
    public static final String TITLE = "title";
    public static final String DESCRIPTION = "description";
    public static final String PROPERTIES = "properties";
    public static final String PATTERN_PROPERTIES = "patternProperties";
    public static final String REQUIRED = "required";
    public static final String ITEMS = "items";
    public static final String ADDITIONAL_ITEMS = "additionalItems";
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String DEFINITIONS = "definitions";
    public static final String DEFS = "$defs";
    public static final String DEPENDENT_SCHEMAS = "dependentSchemas";
    public static final String NOT = "not";
    public static final String IF = "if";
    public static final String THEN = "then";
    public static final String ELSE = "else";
    public static final String CONTAINS = "contains";
    public static final String PROPERTY_NAMES = "propertyNames";
    public static final String UNEVALUATED_ITEMS = "unevaluatedItems";
    public static final String UNEVALUATED_PROPERTIES = "unevaluatedProperties";
    public static final String CONTENT_SCHEMA = "contentSchema";
    public static final String ALL_OF = "allOf";
    public static final String ANY_OF = "anyOf";
    public static final String ONE_OF = "oneOf";
    public static final String PREFIX_ITEMS = "prefixItems";

    /** The reference keyword, and the prefix a reference into {@code $defs} is spelled with. */
    public static final String REF = "$ref";
    public static final String DEFS_PREFIX = "#/$defs/";

}
