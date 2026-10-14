package io.github.synapse4j.openai;

import org.jspecify.annotations.Nullable;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The detail this module spells into its exceptions when the provider reports a failure:
 * {@code ": message [type] (code)}, only the members the provider set.
 *
 * <p>
 * One home for the spelling, because the same document reaches this module through more than one
 * reader and a second copy would drift. A holder of one helper rather than a data class: it is
 * final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OpenAiError {

    /**
     * The detail the three members spell, each written only when the provider set it.
     *
     * @param message the provider's message, or {@code null} when it reported none
     * @param type    the provider's type, or {@code null} when it reported none
     * @param code    the provider's code, or {@code null} when it reported none
     * @return the detail after the caller's own prefix; never {@code null}
     */
    static String detail(@Nullable String message, @Nullable String type, @Nullable String code) {
        StringBuilder detail = new StringBuilder();
        if (message != null) {
            detail.append(": ").append(message);
        }
        if (type != null) {
            detail.append(" [").append(type).append(']');
        }
        if (code != null) {
            detail.append(" (").append(code).append(')');
        }
        return detail.toString();
    }

}
