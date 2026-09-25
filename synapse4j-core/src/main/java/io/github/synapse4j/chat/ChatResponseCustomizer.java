package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatResponse;

/**
 * Adjusts an answer on its way back to the caller, for the details the shared model cannot spell.
 *
 * <p>
 * The inbound counterpart of {@link ChatRequestCustomizer}. The provider module has already
 * translated the wire into the shared model by the time this runs, so what belongs here is the
 * application's own correction of what it is handed: lifting a session id a gateway buried in the
 * extras into the {@link ChatContext}, smoothing a quirk the application knows about, dropping a
 * field it does not want to see. Translating the protocol stays the provider module's job — a
 * customizer adjusts the model's contents, it does not re-read the wire.
 *
 * <p>
 * Customizers belong to a {@link ChatClient} and run on the calling thread after the client's own
 * steps, and the last one's answer is what the caller receives. The sequence among them is the
 * order they were registered in — not a property of this interface: a customizer says what to do,
 * the client decides when. For a streamed answer they run once, when the stream runs to its end; a stream that fails,
 * is closed
 * or is left half-consumed never runs them, the same way a blocking call that failed runs none.
 * The pass happens exactly once per call, so a customizer that changes the response in place
 * should make each change once.
 *
 * <p>
 * The response handed in is the client's own, so a customizer may change it in place and answer
 * it, or leave it alone and answer another one. Either way the answer is stamped with the
 * exchange's {@link ChatContext} before the next customizer sees it: the caller's answer and
 * {@code getResponse()} are always the same instance, and the context rides on
 * whatever comes back — a copy included. What a customizer adjusts is the context's contents;
 * the instance itself belongs to the exchange.
 *
 * <p>
 * The client passed in is the one applying the customizer, carrying the same meaning as in
 * {@link ChatRequestCustomizer}: the layer the customizer was registered on, for reading rather
 * than for calling back into.
 */
@FunctionalInterface
public interface ChatResponseCustomizer extends ChatCustomizer<ChatResponse> {

}
