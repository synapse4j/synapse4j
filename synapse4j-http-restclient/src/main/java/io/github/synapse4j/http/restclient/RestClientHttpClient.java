package io.github.synapse4j.http.restclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.NonNull;
import lombok.extern.java.Log;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpBody;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import org.jspecify.annotations.Nullable;

/**
 * The {@link HttpClient} SPI implemented on Spring's {@link RestClient}.
 *
 * <p>
 * Design stance: this class uses the {@code RestClient} abstraction and nothing beneath it. It never
 * picks or replaces a request factory, never models redirects or restricted headers, never sets a
 * socket timer — everything about the transport underneath (which factory answers, whether and how
 * redirects are followed, which header lines it drops, how long a connection may sit) belongs to
 * whoever built the {@code RestClient} handed to a constructor, and behaves here exactly as it does
 * for every other user of that client. {@code RestClient} is the seam chosen for exactly that
 * reason: it is a configurable front door, and everything configured on it rides along here without
 * this class knowing about it — request interceptors, {@code Observation} instrumentation,
 * authentication, retries.
 *
 * <p>
 * Behavioral contract, as seen by a caller of {@link #send(HttpRequest)}:
 *
 * <ul>
 * <li>The HTTP status is returned as-is; the exchange runs no status handling, so nothing about a
 * 4xx or 5xx is treated as an error.</li>
 * <li>The body is the live response stream, to be read on the caller's thread. Closing the response
 * releases the connection and cancels a body still in flight.</li>
 * <li>A request body is handed over the way {@link HttpBody} describes it: bytes the body already
 * holds go out with their length and without being written first, a body that can only be written
 * is streamed as it comes — its length unknown until it has been written — and
 * {@link HttpOptions#BUFFERED} trades that streaming for one copy of the body in memory, gathered
 * before the request goes out.</li>
 * <li>A {@link HttpOptions#getResponseTimeout() response timeout}, whether the request set it or
 * this client's own options carry it, is not applied — the {@code RestClient} abstraction has no
 * per-request timeout — and is reported once as a warning rather than on every call: the timer to
 * set is the request factory's, for example {@code JdkClientHttpRequestFactory.setReadTimeout},
 * which bounds the wait for the response headers and never the reading of the body.</li>
 * <li>A {@link HttpOptions#getBodyWriteMode() bodyWriteMode} this implementation does not know is
 * refused before the request is built and before the body is looked at: a caller who asked for one
 * thing must not silently get another.</li>
 * <li>A transport failure the call could not get an answer through — DNS, connect, TLS, timeout —
 * arrives wrapped by Spring and is rethrown as {@link SynapseException} with the message
 * {@code "HTTP call failed: <method> <url>"}. Any other {@code RuntimeException} passes through
 * untouched.</li>
 * </ul>
 *
 * <p>
 * Transport-owned behavior follows the {@code RestClient} this client was handed: redirects,
 * restricted headers and socket configuration (connect timeout, SSL, proxy) are that transport's
 * to decide, while interceptors and {@code Observation} observability configured on the client
 * apply to every request this class builds. This class holds the delegate and its own
 * {@link HttpOptions} and nothing else, and is safe to share across threads.
 */
// The JDK's own logger: no dependency of ours at all, and an application bridges JUL into whatever
// it logs with — spring-boot-starter-logging ships jul-to-slf4j, so Boot apps see this configured.
@Log
public class RestClientHttpClient implements HttpClient {

    /** The client requests are built on and sent through; the transport beneath it is its own. */
    private final RestClient delegate;

    /** The options this client falls back to for whatever a request does not set itself. */
    private final HttpOptions options;

    /** The first responseTimeout this client saw has been reported; later ones stay quiet. */
    private final AtomicBoolean responseTimeoutWarned = new AtomicBoolean();

    /**
     * Creates a client on a default {@code RestClient}, with {@link HttpOptions#defaults()} of its
     * own. Which request factory answers is the classpath's choice, exactly as it is for any other
     * {@code RestClient} built without one.
     */
    public RestClientHttpClient() {
        this(RestClient.builder().build(), null);
    }

    /**
     * Creates a client on a caller-supplied {@code RestClient}, with
     * {@link HttpOptions#defaults()} of its own.
     *
     * @param delegate the RestClient to send through; never {@code null}
     */
    public RestClientHttpClient(RestClient delegate) {
        this(delegate, null);
    }

    /**
     * Creates a client on a caller-supplied {@code RestClient}, falling back to the given options
     * for whatever a request does not set itself.
     *
     * <p>
     * This is where a caller says once what their requests usually want instead of repeating it on
     * every request. What the given options leave unset falls back to
     * {@link HttpOptions#defaults()}, so no setting is ever missing by the time a request is
     * built. Transport-level configuration — the request factory, interceptors, redirects — stays
     * where it was configured: on the {@code RestClient} itself.
     *
     * @param delegate the RestClient to send through; never {@code null}
     * @param options  the options to fall back to, or {@code null} for the standard ones
     */
    public RestClientHttpClient(@NonNull RestClient delegate, @Nullable HttpOptions options) {
        this.delegate = delegate;
        this.options = HttpOptions.effective(options, HttpOptions.defaults());
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        HttpOptions effective = HttpOptions.effective(request.getOptions(), this.options);
        requireKnown(effective.getBodyWriteMode());
        if (effective.getResponseTimeout() != null && responseTimeoutWarned.compareAndSet(false, true)) {
            // Ignoring a setting that cannot be honoured is one thing; saying it again on every call
            // would be another — the setting is named once, and the fix is named with it.
            log.warning("responseTimeout is ignored: the RestClient abstraction has no per-request timeout — "
                    + "set it on the request factory instead (e.g. JdkClientHttpRequestFactory.setReadTimeout)");
        }
        RestClient.RequestBodySpec spec = delegate.method(HttpMethod.valueOf(request.getMethod()))
                .uri(request.getUrl());
        request.getHeaders()
                .forEach((name, values) -> values.forEach(value -> spec.header(name, value)));
        setBody(spec, request, effective.getBodyWriteMode());
        try {
            // close=false keeps the response open on the way out: the body it carries is meant to be
            // read on the caller's thread after send() has returned, so the exchange must not release it.
            return spec.exchange((ignored, clientResponse) -> {
                DefaultHttpResponse response = new DefaultHttpResponse();
                try {
                    response.setStatusCode(clientResponse.getStatusCode().value());
                    // Spring 7's HttpHeaders is no longer a map, so the lines are copied one name
                    // at a time — into this response's own map, which is never replaced.
                    clientResponse.getHeaders()
                            .forEach((name, values) -> response.getHeaders().put(name, values));
                    response.setBody(clientResponse.getBody());
                } catch (IOException e) {
                    // The caller never sees this response, so nothing else will release the connection
                    // it holds — release it here, before the failure leaves the exchange.
                    clientResponse.close();
                    throw new SynapseException("HTTP call failed: " + request.getMethod() + " "
                            + request.getUrl(), e);
                }
                // The options the exchange ran under travel with the response, so the event stream it
                // hands out is framed with the budget this call asked for — and no caller has to merge
                // the request's options with this client's own a second time.
                response.setOptions(effective);
                return response;
            }, false);
        } catch (ResourceAccessException e) {
            // Spring wraps every transport-level IOException in this: the call never got an answer through.
            throw new SynapseException("HTTP call failed: " + request.getMethod() + " " + request.getUrl(), e);
        }
    }

    /**
     * Hands one request body to the spec by the cheapest route that fits: bytes the body already
     * holds go out with their length, a body that has to be written is streamed as it comes, or
     * gathered first when {@link HttpOptions#BUFFERED} asks for that. The mode was checked before
     * this is reached, so every route here is one this implementation knows.
     */
    private static void setBody(RestClient.RequestBodySpec spec, HttpRequest request, @Nullable String mode) {
        HttpBody body = request.getBody();
        if (body == null) {
            return;
        }
        ByteBuffer buffer = body.buffer();
        if (buffer != null) {
            sendReadyBytes(spec, buffer);
        } else if (HttpOptions.BUFFERED.equals(mode)) {
            sendReadyBytes(spec, gathered(request, body));
        } else {
            spec.body(new StreamedBody(body));
        }
    }

    /**
     * Bytes already in hand: the length travels in the headers and the body callback hands the bytes
     * over as they are, so nothing is written and read back.
     */
    private static void sendReadyBytes(RestClient.RequestBodySpec spec, ByteBuffer buffer) {
        spec.headers(headers -> headers.setContentLength(buffer.remaining()));
        spec.body(new ReadyBytesBody(buffer));
    }

    /**
     * A body written out first, so that it can go out with a {@code Content-Length}: one copy of the
     * body in memory in exchange for a request that carries its length. This is what
     * {@link HttpOptions#BUFFERED} asks for, and the only failure it can meet before the call goes
     * out is the body's own.
     */
    private static ByteBuffer gathered(HttpRequest request, HttpBody body) {
        ByteArrayOutputStream gathered = new ByteArrayOutputStream();
        try {
            body.writeTo(gathered);
        } catch (IOException e) {
            throw new SynapseException(
                    "HTTP request body failed: " + request.getMethod() + " " + request.getUrl(), e);
        }
        return ByteBuffer.wrap(gathered.toByteArray());
    }

    /**
     * Refuses a mode this implementation does not know rather than taking it for the default: a
     * caller who asked for one thing must not silently get another. It is checked before the body is
     * looked at, so a mode that is wrong is wrong whatever the body happens to be.
     */
    private static void requireKnown(String mode) {
        if (!HttpOptions.STREAMED.equals(mode) && !HttpOptions.BUFFERED.equals(mode)) {
            throw new IllegalArgumentException("unsupported bodyWriteMode '" + mode + "': this implementation "
                    + "supports " + HttpOptions.STREAMED + " and " + HttpOptions.BUFFERED);
        }
    }

    /**
     * Bytes a body already holds, handed over through the callback {@code RestClient} asks for. The
     * buffer's position is never advanced — each write works off a fresh view of it — so a second
     * write, whether a retry or a redirect asks for one, starts from the first byte again.
     */
    private static final class ReadyBytesBody implements StreamingHttpOutputMessage.Body {

        private final ByteBuffer bytes;

        ReadyBytesBody(ByteBuffer bytes) {
            this.bytes = bytes;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            ByteBuffer view = bytes.duplicate();
            if (view.hasArray()) {
                out.write(view.array(), view.arrayOffset() + view.position(), view.remaining());
                return;
            }
            // A direct buffer or a read-only view has no array to write from, so it is copied out in
            // one piece — the only case this path copies at all.
            byte[] chunk = new byte[view.remaining()];
            view.get(chunk);
            out.write(chunk);
        }

        @Override
        public boolean repeatable() {
            // HttpBody already commits every write to producing the same bytes, so telling Spring
            // this body may go out more than once is the truth rather than a promise of buffering.
            return true;
        }
    }

    /**
     * A body written straight through to the transport as it comes: nothing is held beyond what the
     * write itself buffers, and the length stays unknown until the write finishes — which is what
     * {@link HttpOptions#STREAMED} asks for.
     */
    private static final class StreamedBody implements StreamingHttpOutputMessage.Body {

        private final HttpBody body;

        StreamedBody(HttpBody body) {
            this.body = body;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            // The sink is the transport's; HttpBody never closes what it is handed.
            try {
                body.writeTo(out);
            } catch (Exception failure) {
                // Spring's body bridge reports every Exception from the writer to the transport, so
                // an Exception reaches the caller as the transport's failure, unwrapped here.
                throw failure;
            } catch (Error failure) {
                // The same bridge catches only Exception: an Error thrown here would kill its
                // writer thread without a terminal signal and leave the request waiting for a
                // response that never comes. Hand it over as an I/O failure instead — what matters
                // is that the call ends and the caller can see the failure.
                throw new IOException("the request body failed with " + failure, failure);
            }
        }

        @Override
        public boolean repeatable() {
            // HttpBody already commits every write to producing the same bytes, so a retry or a
            // redirect re-running this write is the contract rather than a second guess.
            return true;
        }
    }

}
