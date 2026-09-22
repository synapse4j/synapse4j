package io.github.synapse4j.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseException;

class DefaultHttpResponseTest {

    @Test
    void aResponseWithoutAContentTypeHasNoEventStream() {
        assertNull(response("data: one\n\n", Map.of()).sseEventStream());
    }

    @Test
    void aResponseOfAnotherMediaTypeHasNoEventStream() {
        DefaultHttpResponse response = response("{}", Map.of("Content-Type", List.of("application/json")));

        assertNull(response.sseEventStream());
    }

    @Test
    void theMediaTypeIsReadWithoutCaseAndWithItsParametersCutOff() {
        DefaultHttpResponse response = response("data: one\n\n",
                Map.of("content-type", List.of("TEXT/EVENT-STREAM; charset=utf-8")));

        assertNotNull(response.sseEventStream());
    }

    @Test
    void everyCallAnswersWithTheSameEventStream() {
        DefaultHttpResponse response = response("data: one\n\n", eventStream());

        SseEventStream first = response.sseEventStream();

        assertSame(first, response.sseEventStream());
    }

    @Test
    void theEventStreamReadsTheBodyAsFrames() {
        DefaultHttpResponse response = response("data: one\n\ndata: two\n\n", eventStream());

        SseEventStream events = response.sseEventStream();

        assertEquals("one", events.next().getData());
        assertEquals("two", events.next().getData());
        assertFalse(events.hasNext());
    }

    @Test
    void theFrameBudgetIsTheOneTheExchangeRanUnder() {
        HttpOptions options = HttpOptions.defaults();
        options.setMaxFrameBytes(32);
        DefaultHttpResponse response = response("data: " + "x".repeat(100) + "\n\n", eventStream(), options);

        SseEventStream events = response.sseEventStream();

        SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
        assertTrue(thrown.getMessage().contains("32"), thrown.getMessage());
    }

    @Test
    void aResponseHandedNoOptionsStillGetsAnEventStream() throws IOException {
        try (DefaultHttpResponse response = new DefaultHttpResponse()) {
            response.setStatusCode(200);
            response.getHeaders().putAll(eventStream());
            response.setBody(new ByteArrayInputStream("data: one\n\n".getBytes(UTF_8)));

            SseEventStream events = response.sseEventStream();

            assertNotNull(events);
            assertEquals("one", events.next().getData());
        }
    }

    @Test
    void theFallbackBudgetIsTheOneTheLibraryDefaultsTo() throws IOException {
        try (DefaultHttpResponse response = new DefaultHttpResponse()) {
            response.setStatusCode(200);
            response.getHeaders().putAll(eventStream());
            response.setBody(new ByteArrayInputStream(
                    ("data: " + "x".repeat(300 * 1024) + "\n\n").getBytes(UTF_8)));

            SseEventStream events = response.sseEventStream();

            SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
            assertTrue(thrown.getMessage().contains(String.valueOf(HttpOptions.DEFAULT_MAX_FRAME_BYTES)),
                    thrown.getMessage());
        }
    }

    @Test
    void closingTheResponseReleasesTheBodyThroughTheStreamItHandedOut() throws IOException {
        TrackingStream body = new TrackingStream("data: one\n\n");
        DefaultHttpResponse response = response(body, eventStream());

        response.sseEventStream();
        response.close();

        assertTrue(body.closed);
    }

    @Test
    void closingTheResponseClosesTheBodyWhenNoStreamWasAskedFor() throws IOException {
        TrackingStream body = new TrackingStream("data: one\n\n");
        DefaultHttpResponse response = response(body, eventStream());

        response.close();

        assertTrue(body.closed);
    }

    private static Map<String, List<String>> eventStream() {
        return Map.of("Content-Type", List.of("text/event-stream"));
    }

    private static DefaultHttpResponse response(String body, Map<String, List<String>> headers) {
        return response(body, headers, HttpOptions.defaults());
    }

    private static DefaultHttpResponse response(String body, Map<String, List<String>> headers,
            HttpOptions options) {
        return response(new ByteArrayInputStream(body.getBytes(UTF_8)), headers, options);
    }

    private static DefaultHttpResponse response(InputStream body, Map<String, List<String>> headers) {
        return response(body, headers, HttpOptions.defaults());
    }

    private static DefaultHttpResponse response(InputStream body, Map<String, List<String>> headers,
            HttpOptions options) {
        DefaultHttpResponse response = new DefaultHttpResponse();
        response.setStatusCode(200);
        response.getHeaders().putAll(headers);
        response.setBody(body);
        response.setOptions(options);
        return response;
    }

    /** A body that remembers being closed, which is how a released connection shows. */
    private static class TrackingStream extends ByteArrayInputStream {

        private boolean closed;

        TrackingStream(String text) {
            super(text.getBytes(UTF_8));
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            super.close();
        }
    }

}
