package io.github.synapse4j.openai.wire;

import java.util.List;

import lombok.Data;

/**
 * The body of a {@code 200} from {@code /chat/completions}, field for field.
 */
@Data
public class ChatCompletionResponse {

    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

}
