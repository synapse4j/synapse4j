package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;

/**
 * The hooks a client runs around an exchange: whatever a step needs, overridden; the rest, left
 * alone. Each hook names the step it runs at — {@link #customizeRequest} before the request goes
 * out, {@link #customizeResponse} on the way back, {@link #customizeStreamEvent} between a
 * stream's source and its fold — and does nothing unless overridden, so one instance carries the
 * steps its author cares about and is silent at the others.
 *
 * <p>
 * What runs when and where is the client's business. The value a hook is handed is the one the
 * client already holds — the caller's request on the way out, the exchange's answer on the way
 * back, the stream's own event in between — and a hook changes it in place. There is nothing to
 * answer: the identity the rest of the exchange relies on — the request a tool loop grows, the
 * answer the context records — is kept by construction rather than by a rule.
 *
 * <p>
 * An instance joins the client's chains through {@link ChatClient#addChatCustomizer(ChatCustomizer)}.
 */
public interface ChatCustomizer {

    /**
     * Adjusts the request before it is validated and sent.
     *
     * @param client  the client running this hook; never {@code null}
     * @param request the request as it reached this step; never {@code null}
     */
    default void customizeRequest(ChatClient client, ChatRequest request) {
    }

    /**
     * Adjusts the answer after the client has finished with it.
     *
     * @param client   the client running this hook; never {@code null}
     * @param response the answer as it reached this step; never {@code null}
     */
    default void customizeResponse(ChatClient client, ChatResponse response) {
    }

    /**
     * Adjusts a stream's event between the stream's source and its folding — the fold and the
     * caller both see what this hook left.
     *
     * @param client the client running this hook; never {@code null}
     * @param event  the event as it reached this step; never {@code null}
     */
    default void customizeStreamEvent(ChatClient client, ChatStreamEvent event) {
    }

}
