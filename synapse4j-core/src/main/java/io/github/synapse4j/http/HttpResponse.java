package io.github.synapse4j.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * One HTTP response: status, headers, and the body as a stream.
 *
 * <p>
 * The body is always a stream, for every request — streaming and non-streaming callers read the
 * same type, and buffering is a choice the caller makes by reading to the end, not a property of
 * the response. The caller's thread does the reading; {@link #close()} releases the connection and
 * cancels an in-flight body, and must be safe to call from any thread.
 *
 * <p>
 * The status is whatever the server returned. This type has no opinion about 4xx or 5xx and no
 * error subtype: whether a status means failure belongs to the layer that knows what was being
 * asked.
 *
 * <p>
 * Applications receive implementations from the HTTP client they chose. {@link DefaultHttpResponse}
 * is the one every transport reuses; a transport that can hand over a response another way
 * implements this interface itself.
 */
public interface HttpResponse extends AutoCloseable {

    /** The HTTP status code, exactly as received. */
    int getStatusCode();

    /** The response header lines, multiple values per name. Never {@code null}. */
    Map<String, List<String>> getHeaders();

    /** The body stream. Read on the caller's thread; closed by {@link #close()}. */
    InputStream getBody();

    /**
     * The event stream of this response, when it is one: {@code null} unless the response is a
     * {@code text/event-stream}.
     *
     * <p>
     * Every call answers with the same stream, because the body it reads can be consumed only once:
     * the response holds one event stream, not a new one per call. Consuming it is the caller's
     * business, and closing it closes the body.
     *
     * @return the event stream, or {@code null} when this response carries none
     */
    SseEventStream sseEventStream();

    /**
     * Releases the connection backing this response, cancelling the body if it is still in flight.
     * Idempotent and safe to call from any thread, including without having read anything.
     */
    @Override
    void close() throws IOException;

}
