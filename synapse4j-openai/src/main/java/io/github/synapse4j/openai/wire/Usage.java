package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * Token accounting of a completion, as reported in the response's {@code usage} field.
 */
@Data
public class Usage {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private PromptTokensDetails promptTokensDetails;

    /** Breakdown of the prompt tokens; only the fields this cut maps are modelled. */
    @Data
    public static class PromptTokensDetails {
        private Integer cachedTokens;

    }

}
