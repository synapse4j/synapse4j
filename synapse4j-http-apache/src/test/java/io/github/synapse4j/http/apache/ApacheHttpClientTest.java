package io.github.synapse4j.http.apache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpBody;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.http.SseEventStream;

class ApacheHttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private ApacheHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        // Daemon executor threads so a stalled handler can never hold the JVM or the build.
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new ApacheHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postRoundTripsMethodPathHeadersBodyAndStatus() throws Exception {
        AtomicReference<String> seenMethod = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        AtomicReference<Map<String, List<String>>> seenHeaders = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/echo", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenPath.set(exchange.getRequestURI().getPath());
            seenHeaders.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            seenBody.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("X-Echoed", "yes");
            byte[] out = "hello back".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/echo");
        request.setMethod(HttpRequest.POST);
        request.getHeaders().put("X-Test", List.of("one", "two"));
        request.setBody(HttpBody.of("ping".getBytes(UTF_8)));

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
            // Response names arrive lower-cased, whichever transport handed the response over.
            assertEquals(List.of("yes"), response.getHeaders().get("x-echoed"));
            assertEquals("hello back", new String(response.getBody().readAllBytes(), UTF_8));
        }

        assertEquals("POST", seenMethod.get());
        assertEquals("/echo", seenPath.get());
        assertEquals(List.of("one", "two"), headerValues(seenHeaders.get(), "X-Test"));
        assertEquals("ping", new String(seenBody.get(), UTF_8));
    }

    @Test
    void aResponseKeepsEveryValueOfARepeatedHeader() throws Exception {
        server.createContext("/multi", exchange -> {
            exchange.getResponseHeaders().add("X-Multi", "first");
            exchange.getResponseHeaders().add("X-Multi", "second");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/multi");

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
            assertEquals(List.of("first", "second"), response.getHeaders().get("x-multi"));
        }
    }

    @Test
    void aBodyThatHoldsItsBytesGoesOutWithALength() throws Exception {
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        AtomicReference<Map<String, List<String>>> seenHeaders = new AtomicReference<>();
        server.createContext("/ready", exchange -> {
            seenBody.set(exchange.getRequestBody().readAllBytes());
            seenHeaders.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/ready");
        request.setMethod(HttpRequest.POST);
        request.setBody(HttpBody.of("ping".getBytes(UTF_8)));

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
        }

        assertEquals("ping", new String(seenBody.get(), UTF_8));
        // The bytes were there to be handed over, so the request has a length rather than a chunked
        // framing — which is what a body that had to be written would have got.
        assertEquals(List.of("4"), headerValues(seenHeaders.get(), "Content-Length"));
    }

    @Test
    void aBodyThatCanOnlyBeWrittenIsStreamed() throws Exception {
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        AtomicReference<Map<String, List<String>>> seenHeaders = new AtomicReference<>();
        server.createContext("/streamed", exchange -> {
            seenBody.set(exchange.getRequestBody().readAllBytes());
            seenHeaders.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/streamed");
        request.setMethod(HttpRequest.POST);
        request.setBody(out -> {
            out.write("pi".getBytes(UTF_8));
            out.write("ng".getBytes(UTF_8));
        });

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
        }

        assertEquals("ping", new String(seenBody.get(), UTF_8));
        // Nobody knows how long it is until it has been written, so it goes out chunked.
        assertEquals(List.of("chunked"), headerValues(seenHeaders.get(), "Transfer-Encoding"));
    }

    @Test
    void aBufferedBodyIsGatheredBeforeItIsSent() throws Exception {
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        AtomicReference<Map<String, List<String>>> seenHeaders = new AtomicReference<>();
        server.createContext("/buffered", exchange -> {
            seenBody.set(exchange.getRequestBody().readAllBytes());
            seenHeaders.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpOptions options = HttpOptions.defaults();
        options.setBodyWriteMode(HttpOptions.BUFFERED);
        HttpRequest request = new HttpRequest(baseUrl + "/buffered");
        request.setMethod(HttpRequest.POST);
        request.setOptions(options);
        request.setBody(out -> {
            out.write("pi".getBytes(UTF_8));
            out.write("ng".getBytes(UTF_8));
        });

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
        }

        assertEquals("ping", new String(seenBody.get(), UTF_8));
        // Gathered before sending, so the request carries its length instead of a chunked framing.
        assertEquals(List.of("4"), headerValues(seenHeaders.get(), "Content-Length"));
    }

    @Test
    void aBodyWriteModeThisImplementationDoesNotKnowIsRefusedBeforeTheCallGoesOut() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HttpOptions options = HttpOptions.defaults();
        options.setBodyWriteMode("spooled");
        HttpRequest request = new HttpRequest("http://127.0.0.1:" + freePort + "/nowhere");
        request.setMethod(HttpRequest.POST);
        request.setOptions(options);
        request.setBody(HttpBody.of("ping"));

        // Nothing listens on that port: an IllegalArgumentException reaching the caller means the
        // refusal happened before any connection was attempted, not after one failed.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> client.send(request));
        assertTrue(thrown.getMessage().contains("spooled"), thrown::toString);

        // The mode is wrong whatever the body is, so a request without one is refused the same way.
        request.setBody(null);
        assertThrows(IllegalArgumentException.class, () -> client.send(request));
    }

    @Test
    void aServerErrorStatusIsReturnedNotThrown() throws Exception {
        server.createContext("/boom", exchange -> {
            byte[] out = "server error".getBytes(UTF_8);
            exchange.sendResponseHeaders(500, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/boom");

        try (HttpResponse response = client.send(request)) {
            assertEquals(500, response.getStatusCode());
            assertEquals("server error", new String(response.getBody().readAllBytes(), UTF_8));
        }
    }

    @Test
    void connectionRefusedIsWrapped() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HttpRequest request = new HttpRequest("http://127.0.0.1:" + freePort);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.send(request));
        assertEquals("HTTP call failed: GET http://127.0.0.1:" + freePort, thrown.getMessage());
        assertTrue(causeChainContainsType(thrown, IOException.class), thrown::toString);
    }

    @Test
    void anEventStreamArrivesFrameByFrame() throws Exception {
        // The server holds the second frame back until the latch fires; a client that only hands
        // over the body once the response is complete would block on the first frame until the
        // latch timeout (5s), which the elapsed-time assertion below catches.
        CountDownLatch secondFrame = new CountDownLatch(1);
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write("data: first\n\n".getBytes(UTF_8));
                out.flush();
                secondFrame.await(5, TimeUnit.SECONDS);
                out.write("data: second\n\n".getBytes(UTF_8));
                out.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        HttpRequest request = new HttpRequest(baseUrl + "/sse");

        try (HttpResponse response = client.send(request)) {
            SseEventStream events = response.sseEventStream();
            assertNotNull(events, "a text/event-stream response must hand out an event stream");
            long start = System.nanoTime();
            assertTrue(events.hasNext());
            assertEquals("first", events.next().getData());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 4000,
                    "first frame should arrive before the server sends the second");
            secondFrame.countDown();
            assertTrue(events.hasNext());
            assertEquals("second", events.next().getData());
        }
    }

    @Test
    void theFrameBudgetTravelsWithTheRequest() throws Exception {
        server.createContext("/budget", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: 0123456789abcdef\n\n".getBytes(UTF_8));
            out.close();
        });

        HttpOptions options = new HttpOptions();
        options.setMaxFrameBytes(8);
        HttpRequest request = new HttpRequest(baseUrl + "/budget");
        request.setOptions(options);

        try (HttpResponse response = client.send(request)) {
            SseEventStream events = response.sseEventStream();
            assertNotNull(events);
            SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
            assertTrue(thrown.getMessage().contains("the 8 byte budget"), thrown::toString);
        }
    }

    @Test
    void responseTimeoutAppliesToTheWaitForResponseHeaders() {
        // Ten times the client timeout: the client must give up long before the handler wakes up.
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException | InterruptedException ignored) {
                // The client already gave up; nothing useful left to do.
            } finally {
                exchange.close();
            }
        });

        HttpRequest request = new HttpRequest(baseUrl + "/slow");
        HttpOptions options = new HttpOptions();
        options.setResponseTimeout(Duration.ofMillis(300));
        request.setOptions(options);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.send(request));
        assertTrue(causeChainContainsType(thrown, SocketTimeoutException.class), thrown::toString);
    }

    @Test
    void theClientsOwnOptionsApplyWhenTheRequestSetsNone() {
        HttpOptions options = HttpOptions.defaults();
        options.setResponseTimeout(Duration.ofMillis(300));
        ApacheHttpClient withTimeout = new ApacheHttpClient(HttpClients.createDefault(), options);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException | InterruptedException ignored) {
                // The client already gave up; nothing useful left to do.
            } finally {
                exchange.close();
            }
        });

        SynapseException thrown = assertThrows(SynapseException.class,
                () -> withTimeout.send(new HttpRequest(baseUrl + "/slow")));

        assertTrue(causeChainContainsType(thrown, SocketTimeoutException.class), thrown::toString);
    }

    @Test
    void aRedirectReplaysTheBody() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            // Read the body to the end, so the exchange completes cleanly before the 307 goes out.
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", baseUrl + "/final");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        AtomicReference<byte[]> seenAtTarget = new AtomicReference<>();
        server.createContext("/final", exchange -> {
            seenAtTarget.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/redirect");
        request.setMethod(HttpRequest.POST);
        request.setBody(out -> {
            writes.incrementAndGet();
            out.write("ping".getBytes(UTF_8));
        });

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
        }

        assertEquals("ping", new String(seenAtTarget.get(), UTF_8));
        // One write for the request to /redirect, one for the request the redirect re-sends —
        // HttpBody's contract is that a second write produces the same bytes, and the stock
        // HttpClient 5 chain leans on exactly that.
        assertEquals(2, writes.get());
    }

    @Test
    void aStreamedBodyThatFailsIsReported() throws IOException {
        server.createContext("/failing", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/failing");
        request.setMethod(HttpRequest.POST);
        request.setBody(out -> {
            throw new IOException("no bytes today");
        });

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.send(request));

        assertTrue(causeChainContains(thrown, "no bytes today"), thrown::toString);
    }

    @Test
    void aStreamedBodyThatFailsWithARuntimeExceptionIsReportedRatherThanLeftWaiting() throws IOException {
        server.createContext("/failing", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/failing");
        request.setMethod(HttpRequest.POST);
        request.setBody(out -> {
            throw new IllegalStateException("no bytes today");
        });

        Exception thrown = assertThrows(Exception.class, () -> client.send(request));

        assertTrue(causeChainContains(thrown, "no bytes today"), thrown::toString);
    }

    @Test
    void aStreamedBodyThatFailsWithAnErrorIsReportedRatherThanLeftWaiting() throws IOException {
        server.createContext("/failing", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/failing");
        request.setMethod(HttpRequest.POST);
        request.setBody(out -> {
            throw new AssertionError("no bytes today");
        });

        // Reported whether it arrives as itself or wrapped: what matters is that the call ends.
        Throwable thrown = assertThrows(Throwable.class, () -> client.send(request));

        assertTrue(causeChainContains(thrown, "no bytes today"), thrown::toString);
    }

    @Test
    void closeCancelsABlockedBodyRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                // Send no body bytes: every client read blocks until the response is closed.
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        HttpRequest request = new HttpRequest(baseUrl + "/stall");

        HttpResponse response = client.send(request);
        AtomicBoolean readReturnedNormally = new AtomicBoolean();
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                response.getBody().read();
                readReturnedNormally.set(true);
            } catch (Throwable t) {
                readFailure.set(t);
            }
        });
        try {
            // Give the reader a moment to actually block on the empty body.
            Thread.sleep(200);
            response.close();
            assertTrue(reader.join(Duration.ofSeconds(5)), "reader thread did not exit after close");
            assertFalse(readReturnedNormally.get(), "read should not complete on a stalled body");
            assertNotNull(readFailure.get(), "blocked read should fail when the response is closed");
            assertTrue(readFailure.get() instanceof IOException, readFailure.get()::toString);
        } finally {
            release.countDown();
            response.close();
        }
    }

    @Test
    void aBodyReadToTheEndGivesItsConnectionBackToThePool() throws Exception {
        server.createContext("/pooled", exchange -> {
            byte[] out = "pooled".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        ApacheHttpClient pooled = new ApacheHttpClient(HttpClients.custom()
                .setConnectionManager(pool).build());

        try (HttpResponse response = pooled.send(new HttpRequest(baseUrl + "/pooled"))) {
            assertEquals("pooled", new String(response.getBody().readAllBytes(), UTF_8));
        }

        // Reading the body to its end is what hands the connection back; had only the close been
        // the release, the exchange would have been aborted and the pool left empty instead.
        PoolStats stats = pool.getTotalStats();
        assertEquals(1, stats.getAvailable(), stats::toString);
        assertEquals(0, stats.getLeased(), stats::toString);
    }

    @Test
    void theDefaultConstructorRoundTrips() throws Exception {
        server.createContext("/default", exchange -> {
            byte[] out = "default ok".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/default");

        try (HttpResponse response = new ApacheHttpClient().send(request)) {
            assertEquals(200, response.getStatusCode());
            assertEquals("default ok", new String(response.getBody().readAllBytes(), UTF_8));
        }
    }

    /** The value list of one captured request header, whichever spelling the server-side recorded. */
    private static List<String> headerValues(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    private static boolean causeChainContains(Throwable thrown, String message) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (message.equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean causeChainContainsType(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

}
