package io.github.synapse4j.data;

import org.jspecify.annotations.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * The model's reasoning about its answer, kept apart from the answer itself.
 *
 * <p>
 * A separate type rather than a flag on {@link TextPart} because the distinction is not cosmetic:
 * reasoning is usually hidden from end users while the answer is shown, it is billed and counted
 * separately, and some providers require it to be sent back unchanged on the next turn.
 *
 * <p>
 * Providers attach opaque companions to reasoning — a signature, an encrypted blob — and those do
 * not always belong to this part alone (one protocol attaches them to tool calls as well).
 * They therefore go in {@code getExtras()}, keyed by provider, rather than into a typed field here.
 */
@Getter
@ToString(callSuper = true)
@AllArgsConstructor
public class ReasoningPart extends ContentPart {

    /** The reasoning text. */
    private final @Nullable String text;

    /**
     * The reasoning text and the fields the part carries beside it. Hand-written, because Lombok
     * cannot generate a constructor that calls a super constructor with arguments.
     *
     * @param text   the reasoning text
     * @param extras provider-specific fields, {@code null} for none; frozen on the way in
     */
    public ReasoningPart(@Nullable String text, @Nullable ProviderExtras extras) {
        super(extras);
        this.text = text;
    }

}
