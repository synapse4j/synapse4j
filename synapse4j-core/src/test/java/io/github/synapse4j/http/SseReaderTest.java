package io.github.synapse4j.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

class SseReaderTest {

    @Test
    void readsFramesInOrderWithTheirEventNames() {
        SseReader reader = reader("""
                event: message_start
                data: {"type":"message_start"}

                event: content_block_delta
                data: {"type":"content_block_delta"}

                """);

        List<SseEvent> events = drain(reader);

        assertEquals(2, events.size());
        assertEquals("message_start", events.get(0).getEvent());
        assertEquals("{\"type\":\"message_start\"}", events.get(0).getData());
        assertEquals("content_block_delta", events.get(1).getEvent());
        assertEquals("{\"type\":\"content_block_delta\"}", events.get(1).getData());
    }

    @Test
    void aFrameWithoutAnEventFieldHasNoName() {
        SseReader reader = reader("data: {\"id\":\"chunk\"}\n\n");

        SseEvent event = reader.next();

        assertNull(event.getEvent());
        assertEquals("{\"id\":\"chunk\"}", event.getData());
    }

    @Test
    void severalDataLinesArriveJoinedByNewlines() {
        SseReader reader = reader("event: multi\ndata: first\ndata: second\ndata: third\n\n");

        SseEvent event = reader.next();

        assertEquals("first\nsecond\nthird", event.getData());
    }

    @Test
    void commentsAndUnknownFieldsAreNotEvents() {
        SseReader reader = reader("""
                : keep-alive
                id: 42
                retry: 3000
                event: real
                data: payload

                """);

        List<SseEvent> events = drain(reader);

        assertEquals(1, events.size());
        assertEquals("real", events.get(0).getEvent());
        assertEquals("payload", events.get(0).getData());
    }

    @Test
    void aFrameWithNoDataIsNotAnEvent() {
        SseReader reader = reader("event: empty\n\ndata: real\n\n");

        SseEvent event = reader.next();

        assertEquals("real", event.getData());
        assertFalse(reader.hasNext());
    }

    @Test
    void handlesCarriageReturnAndCarriageReturnLineFeed() {
        SseReader reader = reader("event: crlf\r\ndata: one\r\n\r\nevent: cr\rdata: two\r\r");

        List<SseEvent> events = drain(reader);

        assertEquals(2, events.size());
        assertEquals("crlf", events.get(0).getEvent());
        assertEquals("one", events.get(0).getData());
        assertEquals("cr", events.get(1).getEvent());
        assertEquals("two", events.get(1).getData());
    }

    @Test
    void stripsOnlyOneSpaceAfterTheColon() {
        SseReader reader = reader("data:  two spaces\n\n");

        assertEquals(" two spaces", reader.next().getData());
    }

    @Test
    void anIncompleteTrailingFrameIsDiscarded() {
        SseReader reader = reader("data: complete\n\ndata: never dispatched\n");

        assertEquals("complete", reader.next().getData());
        assertFalse(reader.hasNext());
    }

    @Test
    void readingPastTheEndFollowsIteratorContract() {
        SseReader reader = reader("data: one\n\n");

        assertEquals("one", reader.next().getData());
        assertFalse(reader.hasNext());
        assertThrows(NoSuchElementException.class, reader::next);
    }

    @Test
    void closingClosesTheBody() {
        TrackingStream body = new TrackingStream("data: one\n\n");
        SseReader reader = new SseReader(body, HttpOptions.defaults().getMaxFrameBytes());

        reader.close();
        reader.close();

        assertTrue(body.closed);
    }

    @Test
    void aFailingSourceIsReportedAsTheLibraryOwns() {
        InputStream body = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        try (SseReader reader = new SseReader(body, HttpOptions.defaults().getMaxFrameBytes())) {
            SynapseIOException thrown = assertThrows(SynapseIOException.class, reader::hasNext);

            assertEquals("connection reset", thrown.getCause().getMessage());
        }
    }

    @Test
    void aFrameOverTheBudgetFailsTheRead() {
        SseReader reader = reader("data: " + "x".repeat(100) + "\n\n", 32);

        SynapseException thrown = assertThrows(SynapseException.class, reader::hasNext);

        assertTrue(thrown.getMessage().contains("32"), thrown.getMessage());
    }

    @Test
    void theBudgetCountsUtf8BytesRatherThanCharacters() {
        // "data: 中文" is eight characters but twelve bytes on the wire.
        SseReader reader = reader("data: 中文\n\n", 10);

        assertThrows(SynapseException.class, reader::hasNext);
    }

    @Test
    void dataLinesOfOneFrameAddUpAgainstTheBudget() {
        // Two lines of thirty bytes each: either alone is fine, together they cross a fifty-byte budget.
        SseReader reader = reader("data: " + "x".repeat(24) + "\ndata: " + "x".repeat(24) + "\n\n", 50);

        assertThrows(SynapseException.class, reader::hasNext);
    }

    @Test
    void everyFrameGetsTheBudgetBack() {
        String frame = "data: " + "x".repeat(40) + "\n\n";
        SseReader reader = reader(frame + frame, 64);

        assertEquals(40, reader.next().getData().length());
        assertEquals(40, reader.next().getData().length());
        assertFalse(reader.hasNext());
    }

    @Test
    void theBudgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new SseReader(new ByteArrayInputStream(new byte[0]), 0));
    }

    private static SseReader reader(String text) {
        return reader(text, HttpOptions.defaults().getMaxFrameBytes());
    }

    private static SseReader reader(String text, int maxFrameBytes) {
        return new SseReader(new ByteArrayInputStream(text.getBytes(UTF_8)), maxFrameBytes);
    }

    private static List<SseEvent> drain(SseReader reader) {
        List<SseEvent> events = new ArrayList<>();
        while (reader.hasNext()) {
            events.add(reader.next());
        }
        return events;
    }

    /** A body that remembers being closed. */
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
