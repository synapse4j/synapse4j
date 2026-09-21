package io.github.synapse4j.http.jdk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;

class JdkHttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private JdkHttpClient client;

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
        client = new JdkHttpClient();
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
        request.setBody("ping".getBytes(UTF_8));

        try (HttpResponse response = client.send(request)) {
            assertEquals(200, response.getStatusCode());
            // The JDK normalizes response header names to lowercase.
            assertEquals(List.of("yes"), response.getHeaders().get("x-echoed"));
            assertEquals("hello back", new String(response.getBody().readAllBytes(), UTF_8));
        }

        assertEquals("POST", seenMethod.get());
        assertEquals("/echo", seenPath.get());
        assertEquals(List.of("one", "two"), seenHeaders.get().get("X-test"));
        assertEquals("ping", new String(seenBody.get(), UTF_8));
    }

    @Test
    void nonSuccessStatusIsReturnedNotThrown() throws Exception {
        server.createContext("/limited", exchange -> {
            byte[] out = "slow down".getBytes(UTF_8);
            exchange.sendResponseHeaders(429, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        HttpRequest request = new HttpRequest(baseUrl + "/limited");

        try (HttpResponse response = client.send(request)) {
            assertEquals(429, response.getStatusCode());
            assertEquals("slow down", new String(response.getBody().readAllBytes(), UTF_8));
        }
    }

    @Test
    void bodyArrivesIncrementallyBeforeTheResponseCompletes() throws Exception {
        // The server holds the second line back until the latch fires; a client that only hands
        // over the body once the response is complete would block on readLine() for the latch
        // timeout (5s), which the elapsed-time assertion below catches.
        CountDownLatch firstLineRead = new CountDownLatch(1);
        server.createContext("/stream", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("first\n".getBytes(UTF_8));
                out.flush();
                firstLineRead.await(5, TimeUnit.SECONDS);
                out.write("second\n".getBytes(UTF_8));
                out.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        HttpRequest request = new HttpRequest(baseUrl + "/stream");

        try (HttpResponse response = client.send(request);
                BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(), UTF_8))) {
            long start = System.nanoTime();
            assertEquals("first", reader.readLine());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 4000,
                    "first line should arrive before the server sends the second");
            firstLineRead.countDown();
            assertEquals("second", reader.readLine());
        }
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
        assertTrue(thrown.getCause() instanceof HttpTimeoutException, thrown::toString);
    }

    @Test
    void theClientsOwnOptionsApplyWhenTheRequestSetsNone() throws IOException {
        HttpOptions options = HttpOptions.defaults();
        options.setResponseTimeout(Duration.ofMillis(300));
        JdkHttpClient withDefaults = new JdkHttpClient(java.net.http.HttpClient.newHttpClient(), options);
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
                () -> withDefaults.send(new HttpRequest(baseUrl + "/slow")));

        assertTrue(thrown.getCause() instanceof HttpTimeoutException, thrown::toString);
    }

    @Test
    void connectionRefusedIsWrapped() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        HttpRequest request = new HttpRequest("http://127.0.0.1:" + freePort);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.send(request));
        assertTrue(thrown.getCause() instanceof IOException, thrown::toString);
    }

}
