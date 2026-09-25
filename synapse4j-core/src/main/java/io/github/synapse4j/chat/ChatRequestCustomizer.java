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
 * Customizers belong to a {@link ChatClient} and run on the calling thread, before the request is
 * validated and sent. The sequence among them is the order they were registered in — not a
 * property of this interface: a customizer says what to do, the client decides when. The client
 * applies its own defaults before any of them runs, so every customizer sees them applied.
 *
 * <p>
 * The request handed in is the caller's own, so a customizer may change it in place and answer it,
 * or leave it alone and answer another one. A customizer that changes what it was given should make
 * that change once: the caller may reuse the request, and a request that is written again after a
 * failed attempt passes through here again.
 *
 * <p>
 * The client passed in is the one applying the customizer, so one instance registered on several
 * clients can tell them apart. It is there to be read: a decorating client applies its own list
 * itself and the decorated client applies the inner one, so this is the layer the customizer was
 * registered on, which may be within rather than the one the caller drove. Calling back into
 * {@link ChatClient#chat(ChatRequest)} or {@link ChatClient#stream(ChatRequest)} from here
 * re-enters this very pass and is not supported.
 */
@FunctionalInterface
public interface ChatRequestCustomizer extends ChatCustomizer<ChatRequest> {

}
