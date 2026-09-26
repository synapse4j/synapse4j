package io.github.synapse4j.data;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

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
 *
 * <p>
 * Transport metadata stays out of {@code getExtras()}: headers have their own field, so the open part
 * holds only what came out of the response body.
 */
@Getter
@Setter
@ToString
public class ChatResponse {

    /** The assistant's turn. Never {@code null}; its role is set by the adapter. */
    @NonNull
    private ChatMessage message = new ChatMessage();

    /** Why generation stopped: a {@link ChatFinishReason} constant, or any other provider value. */
    private @Nullable String finishReason;

    /** What the call consumed and produced, or {@code null} when the provider reported none. */
    private @Nullable Usage usage;

    /** The model that answered; the provider's own echo, which may differ from the one requested. */
    private @Nullable String model;

    /** The provider's identifier for this response, or {@code null} when it gives none. */
    private @Nullable String id;

    /**
     * The context this answer rides back on — the instance the prepared request carried, or
     * {@code null} when none did. Filled by the client; never serialized.
     */
    private @Nullable ChatContext context;

    /**
     * Headers the transport reported for this response — a request id, rate-limit counts — and empty
     * when it exposed none. Filled by the adapter; an application has no business writing here.
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /** Provider-specific fields of the response itself, as opposed to one of its parts. */
    private final ProviderExtras extras = new ProviderExtras();

}
