package io.github.synapse4j.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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
 * They therefore go in {@link #getExtras()}, keyed by provider, rather than into a typed field here.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class ReasoningPart extends ContentPart {

    /** The reasoning text. */
    private String text;

}
