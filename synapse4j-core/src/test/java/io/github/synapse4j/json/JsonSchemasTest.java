package io.github.synapse4j.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    @Test
    void shapesOfAnswersTheFormsAKeywordTakes() {
        assertEquals(Set.of(JsonSchemas.Shape.SCHEMA_MAP), JsonSchemas.shapesOf("properties"));
        assertEquals(Set.of(JsonSchemas.Shape.SCHEMA, JsonSchemas.Shape.SCHEMA_LIST),
                JsonSchemas.shapesOf("items"));
        // A keyword the library does not model answers null — no knowledge of it, not "untyped".
        assertNull(JsonSchemas.shapesOf("type"));
        assertNull(JsonSchemas.shapesOf("x-provider-extension"));
    }

}
