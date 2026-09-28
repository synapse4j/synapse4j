package io.github.synapse4j.anthropic;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The event vocabulary of the Messages stream, as the protocol spells it.
 *
 * <p>
 * This protocol names every frame in its SSE {@code event:} field, and repeats the name inside the
 * payload as the {@code type} member; either is what an event's {@code eventType} reports. The
 * values are plain strings, like every value in this library that can grow — the provider adding a
 * kind of event does not need a release of this one.
 *
 * <p>
 * A holder of constants rather than a data class: it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AnthropicEventTypes {

    /** The frame that opens the answer: it carries the message object, its content still empty. */
    public static final String MESSAGE_START = "message_start";

    /** The frame that opens one content block, carrying the block as it stands before it grows. */
    public static final String CONTENT_BLOCK_START = "content_block_start";

    /** The frame that grows the open block by one piece — text, input JSON, or thinking. */
    public static final String CONTENT_BLOCK_DELTA = "content_block_delta";

    /** The frame that closes the open block; the block is complete as of this frame. */
    public static final String CONTENT_BLOCK_STOP = "content_block_stop";

    /** The frame that reports top-level changes: why the answer stopped, and the final counts. */
    public static final String MESSAGE_DELTA = "message_delta";

    /**
     * The frame that ends the answer. A body that stops without it is an answer cut short, not an
     * answer — this protocol's counterpart of OpenAI's {@code [DONE]} sentinel.
     */
    public static final String MESSAGE_STOP = "message_stop";

    /** A keep-alive frame carrying nothing but the fact that the connection is alive. */
    public static final String PING = "ping";

    /** The frame a failure arrives in after the answer was already accepted. */
    public static final String ERROR = "error";

}
