package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * A tool the model may call, as declared in the request's {@code tools} array.
 */
@Data
public class Tool {

    private String type;
    private Function function;

    /** The function payload of a tool declaration; {@code parameters} is a parsed JSON Schema node. */
    @Data
    public static class Function {

        private String name;
        private String description;
        private tools.jackson.databind.JsonNode parameters;

    }

}
