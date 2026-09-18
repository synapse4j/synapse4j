package io.github.synapse4j.data;

import lombok.Data;
import lombok.NonNull;

/**
 * One answer: the assistant's turn, and what the provider reported about it.
 *
 * <p>
 * The turn is a {@link ChatMessage} — the same class a request is built from — so it is appended to
 * the next request as it stands, with no conversion and no loss of the provider-specific fields its
 * parts carry.
 *
 * <p>
 * Everything the provider may not report is nullable, and a field with nothing behind it stays
 * {@code null} rather than being filled with a guess.
 */
@Data
public class ChatResponse {

    /** The assistant's turn. Never {@code null}; its role is set by the adapter. */
    @NonNull
    private ChatMessage message = new ChatMessage();

    /** Why generation stopped: a {@link ChatFinishReason} constant, or any other provider value. */
    private String finishReason;

    /** What the call consumed and produced, or {@code null} when the provider reported none. */
    private Usage usage;

    /** The model that answered; the provider's own echo, which may differ from the one requested. */
    private String model;

    /** The provider's identifier for this response, or {@code null} when it gives none. */
    private String id;

    /** Provider-specific fields of the response itself, as opposed to one of its parts. */
    private final ProviderExtras extras = new ProviderExtras();

}
