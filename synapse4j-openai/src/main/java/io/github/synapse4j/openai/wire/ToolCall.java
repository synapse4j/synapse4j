package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * A tool call the model asks for, in a choice's {@code tool_calls} array. {@code arguments} stays
 * JSON text, mirroring how the shared model keeps tool arguments as text.
 */
@Data
public class ToolCall {

    private String id;
    private String type;
    private Function function;

    /** The function payload of a tool call. */
    @Data
    public static class Function {

        private String name;
        private String arguments;

    }

}
