package io.github.synapse4j.openai.wire;

import lombok.Data;

/**
 * One entry of the array form of a message's {@code content}: a typed part. Only the text variant is
 * modelled — the first cut supports text, and any other part type in a response fails loudly in the
 * adapter rather than being dropped here.
 */
@Data
public class ContentPart {

    private String type;
    private String text;

}
