package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatRequest;

/**
 * Prepares a request on its way out, for the details the shared model cannot spell.
 *
 * <p>
 * The model this library hands to a provider is unified, and a provider's own extensions travel in
 * the extras of the node they belong to. What is left is the middle ground: a shared field whose
 * mapping does not fit one particular endpoint — the same idea the endpoint spells with another
 * name, or a field it refuses outright. This is where that is corrected, before anything is
 * serialized: a value moves into the extras under the name the endpoint wants and the shared field
 * is cleared, or the shared field is cleared on its own when it must not go out at all.
 *
 * <p>
 * Customizers belong to a {@link ChatClient} and run in the order they were added, on the calling
 * thread, before the request is validated and sent.
 *
 * <p>
 * The request handed in is the caller's own, so a customizer may change it in place and answer it,
 * or leave it alone and answer another one. A customizer that changes what it was given should make
 * that change once: the caller may reuse the request, and a request that is written again after a
 * failed attempt passes through here again.
 */
@FunctionalInterface
public interface ChatRequestCustomizer {

    /**
     * Answers the request to send.
     *
     * @param request the request as the caller built it; never {@code null}
     * @return the request to send, which may be the one that was given; never {@code null}
     */
    ChatRequest customize(ChatRequest request);

}
