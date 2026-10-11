package io.github.synapse4j.chat;

import java.util.Iterator;

import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;

/**
 * A streaming answer, pulled event by event, that assembles itself as it is consumed.
 *
 * <p>
 * The {@code Iterable} shape is deliberate: an enhanced {@code for} loop over this interface is the
 * intended way to consume it — {@code break}, {@code continue} and early {@code return} work the
 * way they do in any loop, which the functional operations on a stream express only awkwardly.
 * Pulling is lazy and blocking: {@code hasNext()} waits for the next event to arrive, which is
 * also what backpressures the provider, and the thread that iterates is the thread events arrive
 * on.
 *
 * <p>
 * The fold is the only state kept: each event handed out is folded into an aggregated
 * {@link ChatResponse}, so the same consumption that drives a UI builds the complete answer. It
 * restores what the protocol carries the way a blocking call reads them; the provider's own
 * unmodelled members are gathered from the frames that happened to carry them, so the extras an
 * assembled answer ends up with need not match the ones a single response would have kept.
 *
 * <p>
 * One stream may cover several exchanges: a tool-calling loop's stream keeps opening the next
 * round's answer as the previous one ends, and their events arrive in one sequence — the
 * boundary is the protocol's own, and no marker is synthesized.
 *
 * <p>
 * One pass only: events are not buffered, so there is nothing to iterate a second time. A stream
 * that runs to its end releases its connection by itself — the moment its last event is handed out
 * — so consuming an answer completely needs no close, and a loop that breaks out early is the one
 * that does.
 *
 * <p>
 * Applications receive implementations from {@link ChatClient#stream}. {@link DefaultChatStream}
 * is the one every provider module reuses; a provider that needs different behavior implements
 * this interface itself.
 */
public interface ChatStream extends Iterable<ChatStreamEvent>, AutoCloseable {

    /**
     * The single iterator over this stream. A second call throws {@link IllegalStateException}:
     * events are not buffered, so there is nothing to iterate again; iterating a stream that has
     * been closed throws as well.
     */
    @Override
    Iterator<ChatStreamEvent> iterator();

    /**
     * The answer assembled from every event consumed so far — the exchange in progress when
     * the stream spans several. Before consumption this carries no part of the answer yet; once the
     * loop runs to its end it is the complete one, the turn {@link ChatClient#chat} would have
     * returned, while breaking out of the loop early yields the part consumed so far rather than an
     * error. This method never blocks and never drives consumption — it reports what the iterator has
     * already folded.
     *
     * <p>
     * The fold runs on the thread consuming the iterator, so this is read from that thread too:
     * calling it from another thread while the stream is being consumed races with the fold. Of the
     * methods here, only {@link #close()} is safe to call from any thread.
     *
     * @return the aggregated response; never {@code null}
     */
    ChatResponse aggregatedResponse();

    /**
     * Releases the connection behind this stream, cancelling the response if it is still in
     * flight; on a stream spanning several exchanges this releases the one in progress and
     * starts no further one. Idempotent; safe to call from any thread, including without
     * having consumed anything.
     */
    @Override
    void close();

}
