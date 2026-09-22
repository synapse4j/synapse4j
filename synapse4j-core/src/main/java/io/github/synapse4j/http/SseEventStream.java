package io.github.synapse4j.http;

import java.io.IOException;
import java.util.Iterator;

import io.github.synapse4j.exception.SynapseIOException;

/**
 * A {@code text/event-stream} body read as a sequence of {@link SseEvent} frames.
 *
 * <p>
 * The format is cut by its own rules, not by a protocol's: lines end with CR, LF or CRLF, a line
 * starting with {@code :} is a comment, a blank line dispatches the frame that accumulated before
 * it, and a frame with no {@code data:} line at all is not an event and is skipped — the same rule
 * that makes a keep-alive comment free. Field names other than {@code event} and {@code data} are
 * ignored here; a protocol that needs them reads them from the payload or not at all. One UTF-8 byte
 * order mark at the very start, which the format's grammar permits once, is dropped.
 *
 * <p>
 * The event name travels exactly as the wire carries it: {@code null} when a frame names none, the
 * empty string when it names an empty one. The specification's default of {@code "message"} is a
 * browser EventSource concept; this library stays faithful to what arrives and leaves any
 * defaulting to the consumer.
 *
 * <p>
 * Reading is lazy and blocking: {@link #hasNext()} waits for the next frame to arrive on the calling
 * thread, so a caller that stops pulling stops the provider — the same backpressure the body stream
 * itself has. Nothing is read before the first pull. One stream reads one body, on one thread, and
 * is consumed once: the frames are not buffered, so there is nothing to iterate again.
 *
 * <p>
 * Closing the stream closes the body it was given, which is how a streaming response is cancelled.
 * It is idempotent and safe to call from any thread, including without having pulled anything.
 *
 * <p>
 * A failure of the source is a {@link SynapseIOException} whose cause is the original
 * {@link IOException}. An implementation may cap how much one frame may accumulate; crossing that
 * cap fails the read, and never truncates, since half a frame is worse than none.
 *
 * <p>
 * Applications receive implementations from the HTTP layer they chose. {@link DefaultSseEventStream}
 * is the default one; a transport that can hand over frames another way implements this interface
 * itself.
 */
public interface SseEventStream extends Iterator<SseEvent>, AutoCloseable {

    /**
     * Closes the body behind this stream, cancelling a response that is still in flight. Idempotent,
     * and safe to call from any thread, including without having pulled anything. A failure of the
     * close reaches the caller as an {@link IOException}, like every other transport-level failure
     * in this layer.
     */
    @Override
    void close() throws IOException;

}
