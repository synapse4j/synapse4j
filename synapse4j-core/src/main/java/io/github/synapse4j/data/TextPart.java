package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
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
@ToString(callSuper = true)
@AllArgsConstructor
public class TextPart extends ContentPart {

    /** The text itself. */
    private final @Nullable String text;

    /**
     * The text and the fields the part carries beside it. Hand-written, because Lombok cannot
     * generate a constructor that calls a super constructor with arguments.
     *
     * @param text   the text itself
     * @param extras provider-specific fields, {@code null} for none; frozen on the way in
     */
    public TextPart(@Nullable String text, @Nullable ProviderExtras extras) {
        super(extras);
        this.text = text;
    }

}
