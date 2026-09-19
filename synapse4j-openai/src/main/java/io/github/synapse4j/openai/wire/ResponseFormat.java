package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * The shape the answer should take, as sent in {@code response_format}.
 */
@Data
public class ResponseFormat {

    private String type;
    private JsonSchema jsonSchema;

    /** The schema payload of a {@code json_schema} response format. */
    @Data
    public static class JsonSchema {

        private String name;
        private tools.jackson.databind.JsonNode schema;

    }

}
