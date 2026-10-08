package io.github.synapse4j.http.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Flow;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.BodyWriteMode;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpBody;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;

/**
 * The {@link HttpClient} SPI implemented on the JDK's {@code java.net.http.HttpClient}, adding no
 * dependencies of its own.
 *
 * <p>
 * Behavioral contract, as seen by a caller of {@link #send(HttpRequest)}:
 *
 * <ul>
 * <li>The HTTP status is returned as-is; nothing about a 4xx or 5xx is treated as an error.</li>
 * <li>The body is the live response stream, to be read on the caller's thread. Closing the response
 * releases the connection and cancels a body still in flight.</li>
 * <li>A request body is handed over the way {@link HttpBody} describes it: bytes the body already holds
 * go out without being written, and a body that can only be written is gathered into memory and sent
 * with its length. The JDK pulls the body through a thread of its own, so this transport does not stream
 * a written one: {@link BodyWriteMode#STREAMED} is refused, and {@link BodyWriteMode#AUTO}, the default,
 * gathers.</li>
 * <li>A request with no body and one with an empty body are the same exchange here: the JDK's
 * {@code noBody()} publisher sends {@code Content-Length: 0} for a method that carries a body, so the
 * distinction {@link HttpRequest#getBody()} draws between the two cannot be expressed on this
 * transport.</li>
 * <li>A {@link HttpOptions#getResponseTimeout() response timeout}, whether the request set it or this
 * client's own options carry it, maps to the JDK request builder's {@code timeout}, which — verified
 * empirically — bounds only the wait for the response headers to start arriving, never the reading of
 * the body.</li>
 * <li>The JDK refuses certain <em>restricted headers</em> ({@code Host}, {@code Connection},
 * {@code Content-Length}, {@code Upgrade}, and a few more) and throws
 * {@link IllegalArgumentException} when a request tries to set one. That is a caller bug —
 * the call never went out — and is deliberately left unwrapped rather than surfaced as a
 * {@code SynapseException}.</li>
 * <li>A request that carries its own framing ({@code Content-Length} or {@code Transfer-Encoding})
 * alongside a body is refused, as the other transports refuse it: the JDK takes
 * {@code Transfer-Encoding} as an ordinary header and would add its own framing beside it, so
 * leaving it alone would put two framings on one request.</li>
 * </ul>
 *
 * <p>
 * Socket-level configuration — connect timeout, executor, SSL context, proxy — lives on the JDK client
 * and is reachable through {@link #JdkHttpClient(java.net.http.HttpClient, HttpOptions)}; there is
 * deliberately no per-request equivalent, because such timers are implementation-global, never
 * per-request. This class holds its own {@link HttpOptions} and nothing else, and is safe to share
 * across threads.
 */
public class JdkHttpClient implements HttpClient {

    // Held strongly on purpose: the JDK HttpClient's connection pool is keyed to the instance, and
    // exchanges still in flight have been observed to be dropped when their client is GC'd — a
    // caller building one inline per send would see sporadic failures for no visible reason.
    private final java.net.http.HttpClient delegate;

    /** The options this client falls back to for whatever a request does not set itself. */
    private final HttpOptions options;

    /** Creates a client on a default JDK HttpClient, with {@link HttpOptions#defaults()} of its own. */
    public JdkHttpClient() {
        this(java.net.http.HttpClient.newHttpClient(), null);
    }

    /**
     * Creates a client on a caller-supplied JDK HttpClient, with {@link HttpOptions#defaults()} of its
     * own.
     *
     * @param delegate the JDK client to send through; never {@code null}
     */
    public JdkHttpClient(java.net.http.HttpClient delegate) {
        this(delegate, null);
    }

    /**
     * Creates a client on a caller-supplied JDK HttpClient, falling back to the given options for
     * whatever a request does not set itself.
     *
     * <p>
     * This is where connect timeout, executor, SSL and proxy stay configurable in JDK-land without this
     * library modelling them, and where a caller says once what their requests usually want instead of
     * repeating it on every request. What the given options leave unset falls back to
     * {@link HttpOptions#defaults()}, so no setting is ever missing by the time a request is built.
     *
     * @param delegate the JDK client to send through; never {@code null}
     * @param options  the options to fall back to, or {@code null} for the standard ones
     */
    public JdkHttpClient(java.net.http.HttpClient delegate, @Nullable HttpOptions options) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.options = HttpOptions.effective(options, HttpOptions.defaults());
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        HttpOptions effective = HttpOptions.effective(request.getOptions(), this.options);
        // A body's framing belongs to the transport. The JDK refuses Content-Length as a restricted
        // header but takes Transfer-Encoding as an ordinary one and then frames the body itself, so
        // a request that set it would go out with both framings. Refused here, the way the other two
        // transports refuse the pair.
        if (request.getBody() != null) {
            for (String name : request.getHeaders().keySet()) {
                if ("content-length".equalsIgnoreCase(name) || "transfer-encoding".equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException(
                            "a body's framing belongs to the transport: drop the request's own " + name);
                }
            }
        }
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()));
        request.getHeaders()
                .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.method(request.getMethod(), bodyPublisher(request, effective.getBodyWriteMode()));
        if (effective.getResponseTimeout() != null) {
            builder.timeout(effective.getResponseTimeout());
        }
        try {
            java.net.http.HttpResponse<InputStream> jdkResponse = delegate.send(builder.build(),
                    BodyHandlers.ofInputStream());
            DefaultHttpResponse response = new DefaultHttpResponse();
            response.setStatusCode(jdkResponse.statusCode());
            // The JDK's header names are already lower-cased — the spelling this library's contract
            // promises — but its lists are its own: copy name by name so the map and every value
            // list belong to the response and no caller mutation reaches the JDK's response.
            jdkResponse.headers()
                    .map()
                    .forEach((name, values) -> response.getHeaders().put(name, new ArrayList<>(values)));
            response.setBody(jdkResponse.body());
            // The options the exchange ran under travel with the response, so the event stream it
            // hands out is framed with the budget this call asked for — and no caller has to merge
            // the request's options with this client's own a second time.
            response.setOptions(effective);
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SynapseException("HTTP call interrupted: " + request.getMethod() + " "
                    + request.getUrl(), e);
        } catch (IOException e) {
            // Covers HttpTimeoutException too: the call never got an answer through.
            throw new SynapseException("HTTP call failed: " + request.getMethod() + " " + request.getUrl(), e);
        }
    }

    /**
     * The JDK's publisher for one request body, by the cheapest way that body can be handed over: bytes
     * it already holds go out without being written, and a body that has to be written is gathered.
     *
     * <p>
     * {@link #requireMode} refuses a mode this transport does not take before the body is looked at, so a
     * request that asked for one thing is not quietly sent another way.
     */
    private static BodyPublisher bodyPublisher(HttpRequest request, @Nullable String mode) {
        requireMode(mode);
        HttpBody body = request.getBody();
        if (body == null) {
            return BodyPublishers.noBody();
        }
        ByteBuffer buffer = body.buffer();
        if (buffer != null) {
            return readyBytes(buffer);
        }
        return gathered(request, body);
    }

    /**
     * Refuses a mode this transport does not take: the one the string names, {@link BodyWriteMode#from}
     * refusing a value this library does not define, and {@link BodyWriteMode#STREAMED} refused because
     * this transport gathers every written body rather than streaming it — the JDK pulls the body through
     * a thread of its own, so streaming would add a thread of ours and a lock around it.
     * {@link BodyWriteMode#AUTO} and {@link BodyWriteMode#BUFFERED} both take the gathered route.
     */
    private static void requireMode(@Nullable String mode) {
        BodyWriteMode known = BodyWriteMode.from(mode);
        if (known == BodyWriteMode.STREAMED) {
            throw new IllegalArgumentException("unsupported bodyWriteMode '" + mode
                    + "': this transport gathers the request body");
        }
    }

    /**
     * The bytes a body already holds, handed over without a copy where the JDK can take them: a buffer
     * with a backing array goes out as the array it wraps, and anything else — a direct buffer, a
     * read-only view — is published as the one buffer it is. Either way there is no thread behind it,
     * because the data is already there.
     */
    private static BodyPublisher readyBytes(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return BodyPublishers.ofByteArray(new byte[0]);
        }
        if (buffer.hasArray()) {
            return BodyPublishers.ofByteArray(buffer.array(), buffer.arrayOffset() + buffer.position(),
                    buffer.remaining());
        }
        ByteBuffer ready = buffer.asReadOnlyBuffer();
        return BodyPublishers.fromPublisher(new OneBufferPublisher(ready), ready.remaining());
    }

    /**
     * A body written out first, so that the JDK can send bytes it already has: one copy of the body in
     * memory in exchange for a request that goes out with a {@code Content-Length} and no thread of
     * ours. It is the route {@link BodyWriteMode#BUFFERED} asks for and {@link BodyWriteMode#AUTO} takes,
     * and the only failure it can meet before the call goes out is the body's own.
     */
    private static BodyPublisher gathered(HttpRequest request, HttpBody body) {
        ByteArrayOutputStream gathered = new ByteArrayOutputStream();
        try {
            body.writeTo(gathered);
        } catch (IOException e) {
            throw new SynapseException(
                    "HTTP request body failed: " + request.getMethod() + " " + request.getUrl(), e);
        }
        return BodyPublishers.ofByteArray(gathered.toByteArray());
    }

    /** Publishes one buffer that is already in hand, on demand and without a thread. */
    static final class OneBufferPublisher implements Flow.Publisher<ByteBuffer> {

        private final ByteBuffer buffer;

        OneBufferPublisher(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {

                private volatile boolean sent;

                @Override
                public void request(long n) {
                    if (n <= 0) {
                        // The Flow contract asks for the error rather than silence: a demand of
                        // zero or less is the subscriber's bug, and answering nothing would let
                        // it wait on a reply that never comes.
                        sent = true;
                        subscriber.onError(new IllegalArgumentException("a request must be positive: " + n));
                        return;
                    }
                    if (!sent) {
                        sent = true;
                        // A fresh view per send: what the subscriber is handed gets drained, and
                        // a second subscription — a redirect, a retry — must start from the
                        // beginning rather than inherit an emptied position.
                        subscriber.onNext(buffer.asReadOnlyBuffer());
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    sent = true;
                }
            });
        }
    }

}
