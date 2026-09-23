package io.github.synapse4j.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Text sent to the model, or produced by it.
 *
 * <p>
 * The model's reasoning is not text in this sense and is modelled separately by
 * {@link ReasoningPart}: applications routinely hide it or render it differently, and it may have
 * to be replayed verbatim to the provider that produced it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class TextPart extends ContentPart {

    /** The text itself. */
    private String text;

}
