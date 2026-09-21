package io.github.synapse4j.chat;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * A streaming answer, pulled event by event, that assembles itself as it is consumed.
 *
 * <p>
 * The {@code Iterable} shape is deliberate: an enhanced {@code for} loop over this class is the
 * intended way to consume it — {@code break}, {@code continue} and early {@code return} work the
 * way they do in any loop, which the functional operations on a stream express only awkwardly.
 * Pulling is lazy and blocking: {@code hasNext()} waits for the next event to arrive, which is
 * also what backpressures the provider. The thread that iterates is the thread events arrive on.
 *
 * <p>
 * Each event {@code next()} hands out is also folded into an aggregated {@link ChatResponse}, so
 * the same consumption that drives a UI builds the complete answer: afterwards
 * {@link #aggregatedResponse()} returns exactly what {@link ChatClient#chat} would have —
 * breaking out of the loop early yields the part consumed so far, never an error. Nothing is
 * buffered for this: the aggregation is the only state kept.
 *
 * <p>
 * One pass only. {@link #iterator()} answers the same iterator on every call and a second call
 * throws; iterating a closed stream throws as well. Closing releases the connection behind the
 * stream, cancelling the response if it is still in flight; it is idempotent and safe to call
 * from any thread, and try-with-resources covers the common case. A stream that runs to its end
 * on its own needs no close — the source behind it has nothing left to release.
 *
 * <p>
 * Only a provider module constructs this class, wiring the three things it cannot know: where the
 * events come from, how they fold into the aggregated response, and what releasing the stream
 * means. Applications receive it from {@link ChatClient#stream}.
 */
public final class ChatStream implements Iterable<ChatStreamEvent>, AutoCloseable {

    private final Iterator<ChatStreamEvent> source;

    private final BiConsumer<ChatResponse, ChatStreamEvent> aggregation;

    private final AutoCloseable closeAction;

    private final ChatResponse aggregated = new ChatResponse();

    private StreamIterator iterator;

    private volatile boolean closed;

    /**
     * A stream over the given source.
     *
     * @param source      where events come from, in arrival order; never {@code null}
     * @param aggregation how one consumed event updates the aggregated response; never {@code null}
     * @param closeAction what releasing the stream does — typically closing the HTTP response
     *                        behind it; never {@code null}
     */
    public ChatStream(Iterator<ChatStreamEvent> source, BiConsumer<ChatResponse, ChatStreamEvent> aggregation,
            AutoCloseable closeAction) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.aggregation = Objects.requireNonNull(aggregation, "aggregation must not be null");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction must not be null");
    }

    /**
     * The single iterator over this stream. A second call throws {@link IllegalStateException}:
     * events are not buffered, so there is nothing to iterate again.
     */
    @Override
    public Iterator<ChatStreamEvent> iterator() {
        if (iterator != null) {
            throw new IllegalStateException("this stream has already been iterated");
        }
        iterator = new StreamIterator();
        return iterator;
    }

    /**
     * The answer assembled from every event consumed so far. Before consumption this is an empty
     * response; after the loop runs to its end it is the complete one. This method never blocks
     * and never drives consumption — it reports what the iterator has already folded.
     *
     * @return the aggregated response; never {@code null}
     */
    public ChatResponse aggregatedResponse() {
        return aggregated;
    }

    /**
     * Releases the connection behind this stream, cancelling the response if it is still in
     * flight. Idempotent; safe to call from any thread, including without having consumed
     * anything.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            runClose();
        }
    }

    private void runClose() {
        try {
            closeAction.close();
        } catch (IOException failure) {
            throw new SynapseIOException("Closing the stream failed", failure);
        } catch (Exception failure) {
            throw new SynapseException("Closing the stream failed", failure);
        }
    }

    /** The one iterator: pulls from the source and folds each handed-out event into the aggregation. */
    private final class StreamIterator implements Iterator<ChatStreamEvent> {

        @Override
        public boolean hasNext() {
            checkOpen();
            return source.hasNext();
        }

        @Override
        public ChatStreamEvent next() {
            checkOpen();
            ChatStreamEvent event = source.next();
            aggregation.accept(aggregated, event);
            return event;
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("this stream is closed");
            }
        }

    }

}
