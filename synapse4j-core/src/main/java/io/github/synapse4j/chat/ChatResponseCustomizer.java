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
 * Customizers belong to a {@link ChatClient} and run in the order they were added, after the
 * client's own steps — the context the request carried is already back on the answer — and the
 * last one's answer is what the caller receives. For a streamed answer they run once, when the
 * stream runs to its end; a stream that fails, is closed or is left half-consumed never runs
 * them, the same way a blocking call that failed runs none. The pass happens exactly once per
 * call, so a customizer that changes the response in place should make each change once.
 *
 * <p>
 * The response handed in is the client's own, so a customizer may change it in place and answer
 * it, or leave it alone and answer another one.
 */
@FunctionalInterface
public interface ChatResponseCustomizer {

    /**
     * Answers the response to give the caller.
     *
     * @param response the answer as the client produced it; never {@code null}
     * @return the response to give the caller, which may be the one that was given; never
     *         {@code null}
     */
    ChatResponse customize(ChatResponse response);

}
