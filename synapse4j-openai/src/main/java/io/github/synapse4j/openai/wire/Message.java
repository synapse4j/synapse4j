package io.github.synapse4j.openai.wire;

import java.util.List;

import lombok.Data;

/**
 * One entry of the request's {@code messages} array, and also the shape of a choice's message in the
 * response. {@code content} is untyped because the protocol allows both spellings: a plain string,
 * or an array of typed parts.
 */
@Data
public class Message {

    private String role;
    private Object content;
    private String toolCallId;
    private List<ToolCall> toolCalls;
    private String name;

}
