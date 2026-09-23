package io.github.synapse4j.openai;

import io.github.synapse4j.chat.ChatRequestCustomizer;
import io.github.synapse4j.data.ChatOptions;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Ready-made customizers for the quirks this family of endpoints carries.
 *
 * <p>
 * A quirk belongs here rather than at every call site: one client instance speaks to one base URL,
 * so the policy is chosen once, by whoever wires the client — {@code addChatRequestCustomizer} on
 * the client, before anything is sent.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OpenAiCustomizers {

    /**
     * Speaks the legacy {@code max_tokens} name instead of {@code max_completion_tokens}: the limit
     * set as {@link ChatOptions#getMaxOutputTokens()} moves into the options' extras under the
     * legacy name and the shared field is cleared, so exactly one of the two names goes out. Older
     * models and the many OpenAI-compatible servers answer only to {@code max_tokens}; the official
     * API's current models want the modern one, which is what goes out without this.
     *
     * <p>
     * The change lands on the request's own options and is made once: a retry finds nothing left to
     * move, and a limit set again later is moved again.
     *
     * @return the customizer; never {@code null}
     */
    public static ChatRequestCustomizer legacyMaxTokens() {
        return request -> {
            ChatOptions options = request.getOptions();
            Integer limit = options.getMaxOutputTokens();
            if (limit != null) {
                options.getExtras().put("max_tokens", limit);
                options.setMaxOutputTokens(null);
            }
            return request;
        };
    }

}
