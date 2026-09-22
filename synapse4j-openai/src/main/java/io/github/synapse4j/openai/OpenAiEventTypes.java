package io.github.synapse4j.openai;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The event vocabulary of the chat-completions stream, as the protocol spells it.
 *
 * <p>
 * This protocol discriminates its frames inside the payload rather than in the SSE {@code event:}
 * field: the {@code object} member names the kind of document the frame carries, which is what an
 * event's {@code eventType} reports. The values are plain strings, like every value in this library
 * that can grow — a provider adding a kind of chunk does not need a release of this one.
 *
 * <p>
 * A holder of constants rather than a data class: it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OpenAiEventTypes {

    /** A chunk of the answer: the shape every ordinary frame of this stream has. */
    public static final String CHUNK = "chat.completion.chunk";

    /**
     * The frame that ends the answer. Its data is the literal {@code [DONE]} rather than a document,
     * so it carries nothing beyond saying that the provider has finished sending.
     */
    public static final String DONE = "[DONE]";

}
