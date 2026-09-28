package io.github.synapse4j.http.apache;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.config.Configurable;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpBody;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * The {@link HttpClient} implemented on Apache HttpClient 5's classic (blocking) API.
 *
 * <p>
 * Behavioral contract, as seen by a caller of {@link #send(HttpRequest)}:
 *
 * <ul>
 * <li>The HTTP status is returned as-is; HttpClient 5 reports 4xx and 5xx as ordinary responses, and
 * nothing here turns them into errors.</li>
 * <li>The body is the live response stream, read on the caller's thread. Closing the response aborts
 * the exchange: the HttpClient 5 response is closed with {@link CloseMode#IMMEDIATE}, which cancels a
 * body still in flight instead of draining it to the end — draining is what a graceful close does,
 * and on a stalled server it would block forever — and discards the connection rather than returning
 * a half-read one to the pool. A body read to its end has already handed its connection back to the
 * pool before that, so the close of a fully read response changes nothing. Idempotent and safe to
 * call from any thread; closing an event stream goes down the same path.</li>
 * <li>A request body is handed over the way {@link HttpBody} describes it: bytes the body already
 * holds go out with their length and without being written first; a body that can only be written is
 * streamed straight to the connection on the calling thread — no thread of ours, nothing held beyond
 * the write itself, and a chunked framing since the length is unknown until the write finishes; and
 * {@link HttpOptions#BUFFERED} trades that streaming for one copy of the body in memory gathered
 * before the call, which then carries a {@code Content-Length}. Framing is the entity's: a request
 * that carries its own {@code Content-Length} or {@code Transfer-Encoding} header alongside a body is
 * refused by HttpClient 5's protocol layer rather than reconciled here.</li>
 * <li>A {@link HttpOptions#getResponseTimeout() response timeout}, whether the request set it or this
 * client's own options carry it, is applied per request through HttpClient 5's {@link RequestConfig}.
 * HttpClient 5 implements it as the connection's read timeout for the whole exchange: it bounds the
 * wait for the response headers and any silent gap while the body is being read — stronger than the
 * option's own floor of "headers only", because the library exposes no headers-only timer. Left
 * unset, this class sets no timer at all and the client's own {@code RequestConfig} stands as it was
 * built, which is what {@link HttpOptions#defaults()} means by leaving that to the HTTP library.
 * Connect timeout, TLS, proxy and pool sizing are not modeled here in any case: they live on the
 * {@link CloseableHttpClient} handed to a constructor.</li>
 * <li>A {@link HttpOptions#getBodyWriteMode() bodyWriteMode} this implementation does not know is
 * refused before the request is built and before the body is looked at: a caller who asked for one
 * thing must not silently get another.</li>
 * <li>A transport failure the call could not get an answer through — DNS, connect, TLS, timeout —
 * arrives as {@link SynapseException} with the message {@code "HTTP call failed: <method> <url>"}.
 * A failure the request body throws while being written ends the call too: an {@link IOException}
 * arrives wrapped that way, and any other throwable the body throws travels as itself rather than
 * being swallowed or leaving the call waiting on a response that can no longer come.</li>
 * </ul>
 *
 * <p>
 * Redirects and retries are the execution chain's business, configured on the client this class was
 * handed — the same stance the other transports in this library take toward their own libraries.
 * {@link #ApacheHttpClient()} keeps HttpClient 5's stock behavior: 301, 302, 303, 307 and 308 are
 * followed; a request that failed with a retryable I/O error is retried once a second later if its
 * method is idempotent; and a 429 or 503 is retried once. That is safe to keep because
 * {@link HttpBody#writeTo} already promises a body can be written again for a retry, a redirect or
 * an authentication challenge, and because this library keeps no retry layer of its own above the
 * transport for those defaults to interact with. A caller who wants a different policy builds the
 * {@code CloseableHttpClient} with it.
 *
 * <p>
 * The delegate is never closed by this class: the library's {@link HttpClient} has no close of its
 * own, and whoever built the transport owns its pool, TLS and proxy configuration — including the
 * client {@link #ApacheHttpClient()} builds for itself. This class holds its delegate and its own
 * {@link HttpOptions} and nothing else, and is safe to share across threads.
 */
public class ApacheHttpClient implements HttpClient {

    /** The client requests are sent through; the pools and sockets beneath it are its own. */
    private final CloseableHttpClient delegate;

    /** The options this client falls back to for whatever a request does not set itself. */
    private final HttpOptions options;

    /**
     * Creates a client on a default Apache HttpClient 5 client, with {@link HttpOptions#defaults()}
     * of its own. Its stock execution chain — redirects, retries, connection pool — stays as
     * HttpClient 5 ships it.
     */
    public ApacheHttpClient() {
        this(HttpClients.createDefault(), null);
    }

    /**
     * Creates a client on a caller-supplied Apache HttpClient 5 client, with
     * {@link HttpOptions#defaults()} of its own.
     *
     * @param delegate the HttpClient 5 client to send through; never {@code null}
     */
    public ApacheHttpClient(CloseableHttpClient delegate) {
        this(delegate, null);
    }

    /**
     * Creates a client on a caller-supplied Apache HttpClient 5 client, falling back to the given
     * options for whatever a request does not set itself.
     *
     * <p>
     * This is where connect timeout, TLS, proxy, pool sizing, redirect and retry policy stay
     * configurable in HttpClient-5-land without this library modelling them, and where a caller
     * says once what their requests usually want instead of repeating it on every request. What the
     * given options leave unset falls back to {@link HttpOptions#defaults()}, so no setting is ever
     * missing by the time a request is built.
     *
     * @param delegate the HttpClient 5 client to send through; never {@code null}
     * @param options  the options to fall back to, or {@code null} for the standard ones
     */
    public ApacheHttpClient(@NonNull CloseableHttpClient delegate, @Nullable HttpOptions options) {
        this.delegate = delegate;
        this.options = HttpOptions.effective(options, HttpOptions.defaults());
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        HttpOptions effective = HttpOptions.effective(request.getOptions(), this.options);
        requireKnown(effective.getBodyWriteMode());
        ClassicHttpRequest hcRequest = buildRequest(request, effective.getBodyWriteMode());
        CloseableHttpResponse hcResponse;
        try {
            // executeOpen, not execute: it is the overload HttpClient 5 marks for keeping the
            // response open after the call returns, which is exactly what a streamed body needs.
            // The cast is sound because doExecute is declared to return this client's own
            // CloseableHttpResponse, and executeOpen hands that same object on.
            hcResponse = (CloseableHttpResponse) delegate.executeOpen(null, hcRequest, context(effective));
        } catch (IOException failure) {
            // Covers timeouts and protocol failures too: the call never got an answer through.
            throw new SynapseException("HTTP call failed: " + request.getMethod() + " " + request.getUrl(), failure);
        }
        DefaultHttpResponse response = new DefaultHttpResponse();
        response.setStatusCode(hcResponse.getCode());
        copyHeaders(hcResponse, response.getHeaders());
        try {
            @Nullable
            HttpEntity entity = hcResponse.getEntity();
            InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
            response.setBody(new ResponseBody(content, hcResponse));
        } catch (IOException failure) {
            // The caller never sees this response, so nothing else would release the connection it
            // holds — abort it here, before the failure leaves.
            hcResponse.close(CloseMode.IMMEDIATE);
            throw new SynapseException("HTTP call failed: " + request.getMethod() + " " + request.getUrl(), failure);
        }
        // The options the exchange ran under travel with the response, so the event stream it
        // hands out is framed with the budget this call asked for — and no caller has to merge
        // the request's options with this client's own a second time.
        response.setOptions(effective);
        return response;
    }

    /**
     * The per-request context, carrying the response timeout when the options in effect set one.
     *
     * <p>
     * A context's config replaces the client's default one wholesale, so it starts as a copy of
     * what the client itself was configured with: a response timeout must not quietly drop a
     * caller's pool-lease or proxy settings along the way. When no timeout is in effect no config
     * is set at all, and the client's own stands untouched.
     */
    private HttpClientContext context(HttpOptions effective) {
        HttpClientContext context = HttpClientContext.create();
        Duration timeout = effective.getResponseTimeout();
        if (timeout != null) {
            RequestConfig.Builder config = delegate instanceof Configurable configurable
                    ? RequestConfig.copy(configurable.getConfig())
                    : RequestConfig.custom();
            context.setRequestConfig(config.setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis())).build());
        }
        return context;
    }

    /** One HttpClient 5 request, by the cheapest route that fits the body in hand. */
    private static ClassicHttpRequest buildRequest(HttpRequest request, @Nullable String mode) {
        ClassicRequestBuilder builder = ClassicRequestBuilder.create(request.getMethod())
                .setUri(request.getUrl());
        request.getHeaders()
                .forEach((name, values) -> values.forEach(value -> builder.addHeader(name, value)));
        @Nullable
        HttpBody body = request.getBody();
        if (body != null) {
            builder.setEntity(entity(request, body, mode));
        }
        return builder.build();
    }

    /**
     * The HttpClient 5 entity for one request body, by the cheapest way that body can be handed
     * over: bytes already in hand go out with their length, a body that has to be written is
     * streamed as it comes, or gathered first when {@link HttpOptions#BUFFERED} asks for that. The
     * mode was checked before this is reached, so every route here is one this implementation
     * knows.
     */
    private static HttpEntity entity(HttpRequest request, HttpBody body, @Nullable String mode) {
        ByteBuffer buffer = body.buffer();
        if (buffer != null) {
            return readyBytes(buffer);
        }
        if (HttpOptions.BUFFERED.equals(mode)) {
            return new ByteArrayEntity(gathered(request, body), null);
        }
        return new StreamedBodyEntity(body);
    }

    /**
     * Bytes a body already holds, handed over without a copy where the entity can take them: a
     * buffer with a backing array goes out as the range it wraps, and anything else — a direct
     * buffer, a read-only view — is copied into an array, the one copy this path makes. The
     * buffer's position is never advanced, so a second write starts from the first byte again.
     */
    private static HttpEntity readyBytes(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            return new ByteArrayEntity(buffer.array(), buffer.arrayOffset() + buffer.position(),
                    buffer.remaining(), null);
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        return new ByteArrayEntity(chunk, null);
    }

    /**
     * A body written out first, so that it can go out with a {@code Content-Length}: one copy of
     * the body in memory in exchange for a request that carries its length. This is what
     * {@link HttpOptions#BUFFERED} asks for, and the only failure it can meet before the call goes
     * out is the body's own.
     */
    private static byte[] gathered(HttpRequest request, HttpBody body) {
        ByteArrayOutputStream gathered = new ByteArrayOutputStream();
        try {
            body.writeTo(gathered);
        } catch (IOException e) {
            throw new SynapseException(
                    "HTTP request body failed: " + request.getMethod() + " " + request.getUrl(), e);
        }
        return gathered.toByteArray();
    }

    /**
     * Refuses a mode this implementation does not know rather than taking it for the default: a
     * caller who asked for one thing must not silently get another. It is checked before the body
     * is looked at, so a mode that is wrong is wrong whatever the body happens to be.
     */
    private static void requireKnown(@Nullable String mode) {
        if (!HttpOptions.STREAMED.equals(mode) && !HttpOptions.BUFFERED.equals(mode)) {
            throw new IllegalArgumentException("unsupported bodyWriteMode '" + mode + "': this implementation "
                    + "supports " + HttpOptions.STREAMED + " and " + HttpOptions.BUFFERED);
        }
    }

    /**
     * The response header lines, grouped by name as the wire's case-insensitive names demand: two
     * spellings of one name are one entry, held under whichever arrived first, with the values in
     * the order they arrived.
     */
    private static void copyHeaders(CloseableHttpResponse hcResponse, Map<String, List<String>> headers) {
        for (Header header : hcResponse.getHeaders()) {
            String name = header.getName();
            String key = name;
            for (String existing : headers.keySet()) {
                if (existing.equalsIgnoreCase(name)) {
                    key = existing;
                    break;
                }
            }
            headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(header.getValue());
        }
    }

    /**
     * A request body written straight through to the connection as it comes: the write happens on
     * the thread calling {@code send}, the bytes go out chunked because nobody knows the length
     * until the write finishes, and nothing is held in memory beyond what the write itself buffers.
     */
    private static final class StreamedBodyEntity extends AbstractHttpEntity {

        private final HttpBody body;

        StreamedBodyEntity(HttpBody body) {
            // Chunked, with no content type of our own: the caller's headers say the rest.
            super((String) null, null, true);
            this.body = body;
        }

        /**
         * {@link HttpBody} already commits every write to producing the same bytes, so HttpClient 5
         * re-running this write for a retry or a redirect is the contract rather than a second
         * guess — and telling it so is what lets the stock execution chain keep its retries.
         */
        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public boolean isStreaming() {
            // Not a one-shot source: the content exists by being written, and can be written again.
            return false;
        }

        @Override
        public InputStream getContent() {
            // HttpBody is pushed, not pulled: bytes only exist on their way to a sink, and this
            // transport always writes them that way — through writeTo, never by reading them back.
            throw new UnsupportedOperationException("the request body is written, not read");
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            // The sink belongs to the transport; HttpBody never closes what it is handed.
            body.writeTo(out);
        }

        @Override
        public void close() {
            // The HttpBody behind this entity is the caller's; there is nothing to release here.
        }

    }

    /**
     * The body stream a response hands out: closing it closes the HttpClient 5 response, which is
     * what releases the connection — or cancels a body still in flight, and with it a reader parked
     * on another thread. It deliberately does not close the stream it wraps: HttpClient 5's
     * graceful close drains an unfinished body to the end for reuse, and on a stalled server that
     * drain never returns; the abortive close skips it.
     */
    private static final class ResponseBody extends FilterInputStream {

        private final CloseableHttpResponse response;

        ResponseBody(InputStream in, @NonNull CloseableHttpResponse response) {
            super(in);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            response.close(CloseMode.IMMEDIATE);
        }

    }

}
