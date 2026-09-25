package io.github.synapse4j.chat;

import io.github.synapse4j.data.ChatStreamEvent;

/**
 * Adapts a streamed event on its way out, for the details the shared handling cannot spell.
 *
 * <p>
 * The events are the protocol's own, one per frame, and a provider's dialects reach them
 * untouched: the same idea under another field's name, a quirk only some servers carry. This is
 * where that is corrected — before the event is folded, because the fold is where what it does
 * not understand is let go. A correction here reaches both the folded answer and the caller's
 * loop; one made after the fold could only ever fix the loop's view. The corrected event still
 * has to be one this protocol's fold understands: this customizer translates a dialect, it does
 * not rewrite the protocol.
 *
 * <p>
 * The chain runs between a stream's source and its folding, and what the caller is handed is the
 * very event that was folded — each customizer answers the event the next one, and then the
 * fold, receives. Changing the given event in place and answering it, or answering a copy of its
 * own, both work: the answer is what carries on. An adaptation meant only for the caller's own
 * dispatch belongs in the consuming loop instead, where it sits outside the fold by construction.
 *
 * <p>
 * Customizers belong to a {@link ChatClient} and run on the thread pulling the events, in the
 * order they were registered in, snapshotted when the stream opens: one registered mid-flight
 * joins neither that stream nor its fold. The client passed is the one whose stream runs the
 * chain; a decorating client hands its registrations to the client that folds, so that is the
 * one named here. A blocking call has no events — a registration there never runs.
 *
 * <p>
 * The event handed in is the stream's own, so a customizer may change it in place and answer it,
 * or leave it alone and answer another one.
 */
@FunctionalInterface
public interface ChatStreamEventCustomizer extends ChatCustomizer<ChatStreamEvent> {

}
