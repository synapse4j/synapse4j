package io.github.synapse4j.http;

import io.github.synapse4j.exception.SynapseException;

/**
 * Moves bytes to and from an HTTP endpoint. The seam that lets callers swap the underlying HTTP
 * client without touching anything above it.
 *
 * <p>
 * {@link #send(HttpRequest)} blocks the calling thread until the response headers arrive and returns
 * the body as a stream to be read on that same thread. There is no second, streaming-flavored
 * method: a non-streaming caller simply reads the stream to its end. This is the shape shared by
 * OkHttp and the OpenAI Java SDK; callback-based alternatives exist only to avoid holding a
 * platform thread, which virtual threads make a non-issue.
 *
 * <p>
 * The HTTP status is reported as-is and is never an error here — not even 4xx or 5xx. Whether a
 * status means failure is the caller's decision, made with knowledge of the API being called.
 * Transport-level failures the call could not get an answer through — DNS, connect, TLS, read
 * timeout — are thrown as {@code SynapseException}.
 *
 * <p>
 * Callers must close the returned response, typically via try-with-resources. Closing releases the
 * connection and cancels an in-flight body; it must be safe to call from any thread.
 *
 * <p>
 * Implementations must be stateless and safe to share across threads. A request-level
 * {@link HttpRequest#getResponseTimeout() response timeout}, when set, overrides the implementation's
 * default; implementations need not support every feature a request can express and should say so
 * rather than silently ignore.
 */
public interface HttpClient {

    /**
     * Sends one request and blocks until the response headers arrive.
     *
     * @param request the request to send; never {@code null}
     * @return the response; the caller owns it and must close it
     * @throws SynapseException the call never got an answer (DNS,
     *                              connect, TLS, timeout); the HTTP status is never
     *                              thrown
     */
    HttpResponse send(HttpRequest request);

}
