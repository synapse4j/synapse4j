package io.github.synapse4j.chat;

/**
 * The shape every customizer on this client shares: a hook the client asks, on the calling thread,
 * with a value of one kind as it reaches that step.
 *
 * <p>
 * What runs when and where is the client's business; this interface only says that a customizer is
 * asked with the client applying it and the value as it reached this step. The value is the one the
 * client already holds — the caller's request on the way out, the exchange's answer on the way back,
 * the stream's own event in between — and a customizer changes it in place. There is nothing to
 * answer: the identity the rest of the exchange relies on — the request a tool loop grows, the
 * answer the context records — is kept by construction rather than by a rule.
 *
 * <p>
 * The concrete kinds say what their value is and when their pass runs:
 * {@link ChatRequestCustomizer} on the way out, {@link ChatResponseCustomizer} on the way back,
 * {@link ChatStreamEventCustomizer} between a stream's source and its fold.
 */
@FunctionalInterface
public interface ChatCustomizer<T> {

    /**
     * Adjusts the value as it reached this step.
     *
     * @param client the client applying this customizer; never {@code null}
     * @param target the value as it reached this step; never {@code null}
     */
    void customize(ChatClient client, T target);

}
