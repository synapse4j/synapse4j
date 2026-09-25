package io.github.synapse4j.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

/**
 * One event of a streaming answer: a protocol event, mapped one to one and kept in arrival order.
 *
 * <p>
 * Streaming protocols disagree on how they cut a turn into events — OpenAI chat completions reuse
 * one chunk shape for everything, Anthropic brackets each block with start and stop events, the
 * Responses API names events after the lifecycle they mark. Rather than folding those grammars into
 * one another, an event carries the protocol's own {@code eventType} so an application can dispatch
 * on it; the fields below hold the normalized view for the events that have one. Events that carry
 * no normalized content leave {@code getDelta()} {@code null} and keep their payload in
 * {@code getExtras()}.
 *
 * <p>
 * The {@code eventType} is an open string, like every value in this library that can grow; the
 * well-known ones are declared as constants by the provider module that produces them.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    /**
     * The protocol event this instance maps: the {@code event:} field of an SSE frame, or the
     * payload's discriminator when the protocol has no {@code event:} field. Never {@code null}.
     */
    @NonNull
    private String eventType;

    /**
     * The normalized content of this event — what it adds to the assistant's turn — or {@code null}
     * when the event carries none. A block-start or stop event has nothing to add; a delta event
     * fills the parts with just what arrived in it.
     */
    private ChatMessage delta;

    /**
     * Why generation stopped, when this event says so: a {@link ChatFinishReason} constant, or any
     * other provider value.
     */
    private String finishReason;

    /** What the provider reported consumed and produced, when this event reports it. */
    private Usage usage;

    /** The provider's identifier for the response, or {@code null} when this event gives none. */
    private String id;

    /** The model answering, or {@code null} when this event does not repeat the echo. */
    private String model;

    /** The event's own provider-specific fields — the raw payload's, for events with no normalized view. */
    private final ProviderExtras extras = new ProviderExtras();

}
