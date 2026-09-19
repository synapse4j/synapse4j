package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * One entry of the response's {@code choices} array. Only the fields this cut maps are modelled.
 */
@Data
public class Choice {

    private Integer index;
    private Message message;
    private String finishReason;

}
