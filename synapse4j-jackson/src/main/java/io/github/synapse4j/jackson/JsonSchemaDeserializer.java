package io.github.synapse4j.jackson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.synapse4j.json.BooleanJsonSchema;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import io.github.synapse4j.json.JsonSchemas;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a {@link JsonSchema} from the JSON document it is written as, wherever the mapper meets one.
 *
 * <p>
 * The document is walked token by token — a boolean is a boolean schema, an object is built keyword by
 * keyword — so a sub-schema is built here rather than read into a map first. Which keywords hold
 * sub-schemas, and in which form, is {@link JsonSchemas#shapesOf(String)}. A keyword this library has
 * not modelled is carried as the JSON data it is; a keyword it has modelled, but whose value is in
 * none of its forms, is refused — a string where a schema was due is a document this library does not
 * understand, not one to carry along. An explicit null is carried as it is, whichever keyword holds it,
 * because writing produces such documents and what this library writes it reads back.
 */
class JsonSchemaDeserializer extends ValueDeserializer<JsonSchema> {

    @Override
    public JsonSchema deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return BooleanJsonSchema.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return BooleanJsonSchema.FALSE;
        }
        if (token != JsonToken.START_OBJECT) {
            return ctxt.reportInputMismatch(JsonSchema.class, "a schema is an object or a boolean, not %s", token);
        }
        JsonSchemaBuilder schema = new JsonSchemaBuilder();
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String keyword = p.currentName();
            p.nextToken();
            schema.put(keyword, readValue(p, ctxt, keyword));
        }
        return schema.build();
    }

    /**
     * One keyword's value: one of the forms the keyword is modelled with, or raw JSON data. A
     * sub-schema is read back through the mapper, so the parser is handed the same deserializer this
     * method was reached from.
     */
    private static @Nullable Object readValue(JsonParser p, DeserializationContext ctxt, String keyword) {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        Set<JsonSchemas.Shape> shapes = JsonSchemas.shapesOf(keyword);
        if (shapes == null) {
            return ctxt.readValue(p, Object.class);
        }
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_OBJECT && shapes.contains(JsonSchemas.Shape.SCHEMA_MAP)) {
            Map<String, JsonSchema> schemas = new LinkedHashMap<>();
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                p.nextToken();
                schemas.put(name, ctxt.readValue(p, JsonSchema.class));
            }
            return schemas;
        }
        if (token == JsonToken.START_ARRAY && shapes.contains(JsonSchemas.Shape.SCHEMA_LIST)) {
            List<JsonSchema> schemas = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                schemas.add(ctxt.readValue(p, JsonSchema.class));
            }
            return schemas;
        }
        if (shapes.contains(JsonSchemas.Shape.SCHEMA)
                && (token == JsonToken.START_OBJECT || token == JsonToken.VALUE_TRUE
                        || token == JsonToken.VALUE_FALSE)) {
            return ctxt.readValue(p, JsonSchema.class);
        }
        return ctxt.reportInputMismatch(JsonSchema.class, "the value of %s is not one of %s", keyword, shapes);
    }

}
