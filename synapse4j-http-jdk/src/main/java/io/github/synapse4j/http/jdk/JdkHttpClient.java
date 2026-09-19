package io.github.synapse4j.http.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpClient;
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
 * <li>A per-request {@link HttpRequest#getResponseTimeout() response timeout} maps to the JDK
 * request builder's {@code timeout}, which — verified empirically — bounds only the wait for the
 * response headers to start arriving, never the reading of the body.</li>
 * <li>The JDK refuses certain <em>restricted headers</em> ({@code Host}, {@code Connection},
 * {@code Content-Length}, {@code Upgrade}, and a few more) and throws
 * {@link IllegalArgumentException} when a request tries to set one. That is a caller bug —
 * the call never went out — and is deliberately left unwrapped rather than surfaced as a
 * {@code SynapseException}.</li>
 * </ul>
 *
 * <p>
 * Socket-level configuration — connect timeout, executor, SSL context, proxy — lives on the JDK
 * client and is reachable through {@link #JdkHttpClient(java.net.http.HttpClient)}; there is
 * deliberately no per-request equivalent, because such timers are implementation-global, never
 * per-request. This class holds no other state and is safe to share across threads.
 */
public class JdkHttpClient implements HttpClient {

    // Held strongly on purpose: the JDK HttpClient's connection pool is keyed to the instance, and
    // exchanges still in flight have been observed to be dropped when their client is GC'd — a
    // caller building one inline per send would see sporadic failures for no visible reason.
    private final java.net.http.HttpClient delegate;

    /** Creates a client on a default JDK HttpClient. */
    public JdkHttpClient() {
        this(java.net.http.HttpClient.newHttpClient());
    }

    /**
     * Creates a client on a caller-supplied JDK HttpClient, so that connect timeout, executor, SSL
     * and proxy stay configurable in JDK-land without this library modelling them.
     *
     * @param delegate the JDK client to send through; never {@code null}
     */
    public JdkHttpClient(java.net.http.HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse send(HttpRequest request) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.getUrl()));
        request.getHeaders()
                .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (request.getBody() != null) {
            builder.method(request.getMethod(), BodyPublishers.ofByteArray(request.getBody()));
        } else {
            builder.method(request.getMethod(), BodyPublishers.noBody());
        }
        if (request.getResponseTimeout() != null) {
            builder.timeout(request.getResponseTimeout());
        }
        try {
            java.net.http.HttpResponse<InputStream> jdkResponse = delegate.send(builder.build(),
                    BodyHandlers.ofInputStream());
            HttpResponse response = new HttpResponse();
            response.setStatusCode(jdkResponse.statusCode());
            // The JDK's own header map is already mutable and case-normalized, but it is this
            // implementation's type — copy it so callers own a plain map.
            Map<String, List<String>> headers = new LinkedHashMap<>(jdkResponse.headers().map());
            response.setHeaders(headers);
            response.setBody(jdkResponse.body());
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

}
