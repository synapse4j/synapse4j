package io.github.synapse4j.http.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import io.github.synapse4j.exception.SynapseException;
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
 * go out without being written, a body that can only be written is streamed from a thread of its own so
 * that the JDK's I/O thread never waits for it, and {@link HttpOptions#BUFFERED} trades that thread for
 * one copy of the body in memory.</li>
 * <li>A {@link HttpOptions#getResponseTimeout() response timeout}, whether the request set it or this
 * client's own options carry it, maps to the JDK request builder's {@code timeout}, which — verified
 * empirically — bounds only the wait for the response headers to start arriving, never the reading of
 * the body.</li>
 * <li>The JDK refuses certain <em>restricted headers</em> ({@code Host}, {@code Connection},
 * {@code Content-Length}, {@code Upgrade}, and a few more) and throws
 * {@link IllegalArgumentException} when a request tries to set one. That is a caller bug —
 * the call never went out — and is deliberately left unwrapped rather than surfaced as a
 * {@code SynapseException}.</li>
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
    public JdkHttpClient(java.net.http.HttpClient delegate, HttpOptions options) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.options = HttpOptions.effective(options, HttpOptions.defaults());
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        HttpOptions effective = HttpOptions.effective(request.getOptions(), this.options);
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
            // The JDK's own header map is already mutable and case-normalized, but it is this
            // implementation's type — copy it into the response's own map, which is never replaced.
            response.getHeaders().putAll(jdkResponse.headers().map());
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
     * The JDK's publisher for one request body, by the cheapest way that body can be handed over.
     *
     * <p>
     * A body that holds its bytes is never written; a body that has to be written is streamed, or
     * gathered first when {@link HttpOptions#BUFFERED} asks for that. A mode this implementation does not
     * know is refused rather than taken for the default: a caller who asked for one thing must not
     * silently get another.
     */
    private static BodyPublisher bodyPublisher(HttpRequest request, String mode) {
        requireKnown(mode);
        HttpBody body = request.getBody();
        if (body == null) {
            return BodyPublishers.noBody();
        }
        ByteBuffer buffer = body.buffer();
        if (buffer != null) {
            return readyBytes(buffer);
        }
        if (HttpOptions.BUFFERED.equals(mode)) {
            return gathered(request, body);
        }
        return BodyPublishers.fromPublisher(new StreamingBodyPublisher(body));
    }

    /**
     * Refuses a mode this implementation does not know rather than taking it for the default: a caller
     * who asked for one thing must not silently get another. It is checked before the body is looked at,
     * so a mode that is wrong is wrong whatever the body happens to be.
     */
    private static void requireKnown(String mode) {
        if (!HttpOptions.STREAMED.equals(mode) && !HttpOptions.BUFFERED.equals(mode)) {
            throw new IllegalArgumentException("unsupported bodyWriteMode '" + mode + "': this implementation "
                    + "supports " + HttpOptions.STREAMED + " and " + HttpOptions.BUFFERED);
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
     * ours. This is what {@link HttpOptions#BUFFERED} asks for, and the only failure it can meet before
     * the call goes out is the body's own.
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

                private boolean sent;

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

    /**
     * A body written on a thread of its own, handed to the JDK a chunk at a time as it asks for them.
     *
     * <p>
     * The JDK pulls — every one of its own publishers is something to read — and this library's bodies
     * are written, so the two ends are joined by a thread: the body is written on a virtual thread, and
     * each write waits until the JDK has asked for more before it becomes a chunk. So nothing is held
     * beyond the chunk in hand, the JDK's I/O thread never waits for the body and never runs it, and a
     * body that is slow to produce — a media part read from somewhere slow, say — costs this thread and
     * nothing else.
     *
     * <p>
     * Each subscription gets its own run, since a request may be sent again: {@link HttpBody} promises
     * that a second write produces the same bytes, and a run is one write.
     */
    static final class StreamingBodyPublisher implements Flow.Publisher<ByteBuffer> {

        private final HttpBody body;

        StreamingBodyPublisher(HttpBody body) {
            this.body = body;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            new BodyRun(body, subscriber).start();
        }

        /** One subscription's worth of state: a body written once, one chunk at a time, on request. */
        private static final class BodyRun implements Flow.Subscription {

            private final HttpBody body;

            private final Flow.Subscriber<? super ByteBuffer> subscriber;

            /** How many chunks have been asked for and not handed over yet. */
            private final AtomicLong demand = new AtomicLong();

            private final OutputStream chunkSink = new OutputStream() {

                @Override
                public void write(int b) throws IOException {
                    write(new byte[] { (byte) b }, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    awaitDemand();
                    // The array belongs to the writer and is reused, so the chunk is a copy of it —
                    // the one copy this path makes, and the reason nothing is held beyond one chunk.
                    subscriber.onNext(ByteBuffer.wrap(Arrays.copyOfRange(bytes, offset, offset + length)));
                }
            };

            private volatile Thread producer;

            private volatile boolean cancelled;

            BodyRun(HttpBody body, Flow.Subscriber<? super ByteBuffer> subscriber) {
                this.body = body;
                this.subscriber = subscriber;
            }

            void start() {
                subscriber.onSubscribe(this);
                producer = Thread.ofVirtual().name("synapse4j-request-body").start(this::produce);
            }

            @Override
            public void request(long n) {
                if (n <= 0) {
                    // The error goes out, and the producer is released with it: parked in
                    // awaitDemand for a demand that will now never come, it would otherwise
                    // outlive the subscription on a thread of its own. The cancelled flag keeps
                    // its way out quiet — the subscriber has already been told.
                    cancelled = true;
                    LockSupport.unpark(producer);
                    subscriber.onError(new IllegalArgumentException("a request must be positive: " + n));
                    return;
                }
                demand.updateAndGet(current -> current > Long.MAX_VALUE - n ? Long.MAX_VALUE : current + n);
                LockSupport.unpark(producer);
            }

            @Override
            public void cancel() {
                cancelled = true;
                LockSupport.unpark(producer);
            }

            private void produce() {
                // Set before the first wait, so a request that arrives while this thread is starting
                // still finds someone to wake: unparking a thread that has not parked yet is remembered.
                producer = Thread.currentThread();
                try {
                    body.writeTo(chunkSink);
                    subscriber.onComplete();
                } catch (Throwable failure) {
                    // Whatever the body throws has to reach the subscriber, down to an Error: this
                    // thread exists to give the subscriber an answer, and a producer that dies quietly
                    // would leave the request waiting for a chunk that never comes.
                    if (!cancelled) {
                        subscriber.onError(failure);
                    }
                }
            }

            /** Waits until the subscriber has asked for another chunk, or until the run is over. */
            private void awaitDemand() throws IOException {
                while (demand.get() <= 0) {
                    if (cancelled) {
                        throw new IOException("the request body was cancelled");
                    }
                    LockSupport.park();
                }
                demand.decrementAndGet();
            }
        }
    }

}
