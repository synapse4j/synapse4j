package io.github.synapse4j.chat;

/**
 * The shape every customizer on this client shares: a value of one kind on its way through the
 * client, answered in whatever form carries on.
 *
 * <p>
 * What runs when and where is the client's business; this interface only says that a customizer
 * is asked with the client applying it and the value as it reached this step, and answers the
 * value to continue with — the same instance changed in place, or one of its own. An answer of
 * {@code null} fails loudly. The concrete kinds say what their value is and when their pass
 * runs: {@link ChatRequestCustomizer} on the way out, {@link ChatResponseCustomizer} on the way
 * back, {@link ChatStreamEventCustomizer} between a stream's source and its fold.
 */
@FunctionalInterface
public interface ChatCustomizer<T> {

    /**
     * Answers the value to carry on.
     *
     * @param client the client applying this customizer; never {@code null}
     * @param target the value as it reached this step; never {@code null}
     * @return the value to carry on, which may be the one that was given; never {@code null}
     */
    T customize(ChatClient client, T target);

}
