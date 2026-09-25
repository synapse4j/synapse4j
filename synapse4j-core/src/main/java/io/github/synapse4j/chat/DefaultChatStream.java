package io.github.synapse4j.chat;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * The {@link ChatStream} every provider module reuses. It wires the things only a provider knows:
 * where the events come from, how one event folds into the aggregated response, and what
 * releasing the stream means. The client's event customizer chain is wired in beside them: it
 * runs on each event between the source and the fold, so what is folded and what is handed out
 * are the same event.
 */
public class DefaultChatStream implements ChatStream {

    private final Iterator<ChatStreamEvent> source;

    private final UnaryOperator<ChatStreamEvent> eventPipeline;

    private final BiConsumer<ChatResponse, ChatStreamEvent> aggregation;

    private final AutoCloseable closeAction;

    private final ChatResponse aggregated = new ChatResponse();

    private StreamIterator iterator;

    /** Set by {@link #close()} from any thread; read by the consuming thread. */
    private volatile boolean cancelled;

    /** Set by {@link #release()}, which is the only place the close action runs. */
    private boolean released;

    /** Set by the consuming thread when the source has no more events. */
    private boolean exhausted;

    /**
     * A stream over the given source, with no event customizers.
     *
     * @param source      where events come from, in arrival order; never {@code null}
     * @param aggregation how one consumed event updates the aggregated response; never {@code null}
     * @param closeAction what releasing the stream does — typically closing the HTTP response
     *                        behind it; never {@code null}
     */
    public DefaultChatStream(Iterator<ChatStreamEvent> source, BiConsumer<ChatResponse, ChatStreamEvent> aggregation,
            AutoCloseable closeAction) {
        this(source, UnaryOperator.identity(), aggregation, closeAction);
    }

    /**
     * A stream over the given source.
     *
     * @param source        where events come from, in arrival order; never {@code null}
     * @param eventPipeline the client's event customizer chain, run on each event between the
     *                          source and the fold; never {@code null} — identity when there
     *                          are no customizers
     * @param aggregation   how one consumed event updates the aggregated response; never {@code null}
     * @param closeAction   what releasing the stream does — typically closing the HTTP response
     *                          behind it; never {@code null}
     */
    public DefaultChatStream(Iterator<ChatStreamEvent> source, UnaryOperator<ChatStreamEvent> eventPipeline,
            BiConsumer<ChatResponse, ChatStreamEvent> aggregation, AutoCloseable closeAction) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.eventPipeline = Objects.requireNonNull(eventPipeline, "eventPipeline must not be null");
        this.aggregation = Objects.requireNonNull(aggregation, "aggregation must not be null");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction must not be null");
    }

    @Override
    public Iterator<ChatStreamEvent> iterator() {
        if (iterator != null) {
            throw new IllegalStateException("this stream has already been iterated");
        }
        iterator = new StreamIterator();
        return iterator;
    }

    @Override
    public ChatResponse aggregatedResponse() {
        return aggregated;
    }

    @Override
    public void close() {
        cancelled = true;
        release();
    }

    /**
     * Runs the close action once, whoever asks first — a caller cancelling, the iterator finding
     * the sequence over, or its source failing. A stream that ran to its end is released the moment
     * its last event is handed out, since nothing will read the connection again.
     */
    private synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            closeAction.close();
        } catch (IOException failure) {
            throw new SynapseIOException("Closing the stream failed", failure);
        } catch (Exception failure) {
            throw new SynapseException("Closing the stream failed", failure);
        }
    }

    /** The one iterator: pulls from the source, runs the event chain, and folds what comes out. */
    private final class StreamIterator implements Iterator<ChatStreamEvent> {

        @Override
        public boolean hasNext() {
            checkOpen();
            if (exhausted) {
                return false;
            }
            boolean more;
            try {
                more = source.hasNext();
            } catch (RuntimeException failure) {
                // A source that fails has ended the answer whether or not it says so, and nothing
                // will read the connection again: release here, keeping any close failure beside
                // the original rather than in its place.
                try {
                    release();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            if (!more) {
                // The sequence is over: the answer is complete, and nothing will read the connection
                // again, so it is released here rather than left for a close that may never come.
                exhausted = true;
                release();
            }
            return more;
        }

        @Override
        public ChatStreamEvent next() {
            if (!hasNext()) {
                throw new NoSuchElementException("the stream is exhausted");
            }
            ChatStreamEvent event = eventPipeline.apply(source.next());
            aggregation.accept(aggregated, event);
            return event;
        }

        private void checkOpen() {
            if (cancelled) {
                throw new IllegalStateException("this stream is closed");
            }
        }

    }

}
