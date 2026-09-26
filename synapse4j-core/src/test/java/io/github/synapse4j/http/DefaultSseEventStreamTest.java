package io.github.synapse4j.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

class DefaultSseEventStreamTest {

    @Test
    void readsFramesInOrderWithTheirEventNames() {
        DefaultSseEventStream reader = reader("""
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
        DefaultSseEventStream reader = reader("data: {\"id\":\"chunk\"}\n\n");

        SseEvent event = reader.next();

        assertNull(event.getEvent());
        assertEquals("{\"id\":\"chunk\"}", event.getData());
    }

    @Test
    void severalDataLinesArriveJoinedByNewlines() {
        DefaultSseEventStream reader = reader("event: multi\ndata: first\ndata: second\ndata: third\n\n");

        SseEvent event = reader.next();

        assertEquals("first\nsecond\nthird", event.getData());
    }

    @Test
    void commentsAndUnknownFieldsAreNotEvents() {
        DefaultSseEventStream reader = reader("""
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
        DefaultSseEventStream reader = reader("event: empty\n\ndata: real\n\n");

        SseEvent event = reader.next();

        assertEquals("real", event.getData());
        assertFalse(reader.hasNext());
    }

    @Test
    void handlesCarriageReturnAndCarriageReturnLineFeed() {
        DefaultSseEventStream reader = reader("event: crlf\r\ndata: one\r\n\r\nevent: cr\rdata: two\r\r");

        List<SseEvent> events = drain(reader);

        assertEquals(2, events.size());
        assertEquals("crlf", events.get(0).getEvent());
        assertEquals("one", events.get(0).getData());
        assertEquals("cr", events.get(1).getEvent());
        assertEquals("two", events.get(1).getData());
    }

    @Test
    void stripsOnlyOneSpaceAfterTheColon() {
        DefaultSseEventStream reader = reader("data:  two spaces\n\n");

        assertEquals(" two spaces", reader.next().getData());
    }

    @Test
    void anIncompleteTrailingFrameIsDiscarded() {
        DefaultSseEventStream reader = reader("data: complete\n\ndata: never dispatched\n");

        assertEquals("complete", reader.next().getData());
        assertFalse(reader.hasNext());
    }

    @Test
    void readingPastTheEndFollowsIteratorContract() {
        DefaultSseEventStream reader = reader("data: one\n\n");

        assertEquals("one", reader.next().getData());
        assertFalse(reader.hasNext());
        assertThrows(NoSuchElementException.class, reader::next);
    }

    @Test
    void closingClosesTheBody() throws IOException {
        TrackingStream body = new TrackingStream("data: one\n\n");
        DefaultSseEventStream reader = new DefaultSseEventStream(body, HttpOptions.defaults().getMaxFrameBytes());

        reader.close();
        reader.close();

        assertTrue(body.closed);
    }

    @Test
    void aFailingSourceIsReportedAsTheLibraryOwns() throws IOException {
        InputStream body = new InputStream() {

            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        try (DefaultSseEventStream reader = new DefaultSseEventStream(body,
                HttpOptions.defaults().getMaxFrameBytes())) {
            SynapseIOException thrown = assertThrows(SynapseIOException.class, reader::hasNext);

            assertEquals("connection reset", thrown.getCause().getMessage());
        }
    }

    @Test
    void aFrameOverTheBudgetFailsTheRead() {
        DefaultSseEventStream reader = reader("data: " + "x".repeat(100) + "\n\n", 32);

        SynapseException thrown = assertThrows(SynapseException.class, reader::hasNext);

        assertTrue(thrown.getMessage().contains("32"), thrown.getMessage());
    }

    @Test
    void theBudgetCountsUtf8BytesRatherThanCharacters() {
        // "data: 中文" is eight characters but twelve bytes on the wire.
        DefaultSseEventStream reader = reader("data: 中文\n\n", 10);

        assertThrows(SynapseException.class, reader::hasNext);
    }

    @Test
    void dataLinesOfOneFrameAddUpAgainstTheBudget() {
        // Two lines of thirty bytes each: either alone is fine, together they cross a fifty-byte budget.
        DefaultSseEventStream reader = reader("data: " + "x".repeat(24) + "\ndata: " + "x".repeat(24) + "\n\n", 50);

        assertThrows(SynapseException.class, reader::hasNext);
    }

    @Test
    void everyFrameGetsTheBudgetBack() {
        String frame = "data: " + "x".repeat(40) + "\n\n";
        DefaultSseEventStream reader = reader(frame + frame, 64);

        assertEquals(40, reader.next().getData().length());
        assertEquals(40, reader.next().getData().length());
        assertFalse(reader.hasNext());
    }

    @Test
    void theBudgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultSseEventStream(new ByteArrayInputStream(new byte[0]), 0));
    }

    @Test
    void aLeadingUtf8BomIsSkipped() {
        // The grammar allows one BOM at the very start; the reader must drop it, or the first
        // field name would carry it and the opening frame would be misread.
        DefaultSseEventStream reader = reader("\uFEFFdata: first\n\ndata: second\n\n");

        assertEquals("first", reader.next().getData());
        assertEquals("second", reader.next().getData());
        assertFalse(reader.hasNext());
    }

    @Test
    void aTruncatedBomIsPushedBackOntoTheStream() throws IOException {
        // EF BB without the third byte is not a BOM. Pushed back, the two bytes decode to
        // replacement characters that corrupt the first field name — which is exactly how the
        // test observes they were not dropped: a dropped pair would leave "data: real" intact.
        byte[] head = new byte[] { (byte) 0xEF, (byte) 0xBB };
        byte[] text = "data: real\n\n".getBytes(UTF_8);
        byte[] framed = new byte[head.length + text.length];
        System.arraycopy(head, 0, framed, 0, head.length);
        System.arraycopy(text, 0, framed, head.length, text.length);

        try (DefaultSseEventStream reader = new DefaultSseEventStream(new ByteArrayInputStream(framed),
                HttpOptions.defaults().getMaxFrameBytes())) {
            assertFalse(reader.hasNext());
        }
    }

    @Test
    void anEmptyEventNameIsPreservedAsEmpty() {
        DefaultSseEventStream reader = reader("event:\ndata: x\n\n");

        assertEquals("", reader.next().getEvent());
    }

    private static DefaultSseEventStream reader(String text) {
        return reader(text, HttpOptions.defaults().getMaxFrameBytes());
    }

    private static DefaultSseEventStream reader(String text, int maxFrameBytes) {
        return new DefaultSseEventStream(new ByteArrayInputStream(text.getBytes(UTF_8)), maxFrameBytes);
    }

    private static List<SseEvent> drain(DefaultSseEventStream reader) {
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

    @Test
    void aLineWithNoTerminatorStillStopsAtTheBudget() throws IOException {
        // The budget is enforced as bytes arrive: a server that never sends a newline cannot
        // pin the memory first and fail the read afterwards.
        byte[] endless = "x".repeat(64).getBytes(UTF_8);

        try (DefaultSseEventStream reader = new DefaultSseEventStream(new ByteArrayInputStream(endless), 16)) {
            SynapseException thrown = assertThrows(SynapseException.class, reader::hasNext);

            assertTrue(thrown.getMessage().contains("16"), thrown.getMessage());
        }
    }

    @Test
    void closingWhileTheReaderIsParkedOnTheBodyUnblocksIt() throws InterruptedException {
        SilentStream body = new SilentStream();
        DefaultSseEventStream reader = new DefaultSseEventStream(body,
                HttpOptions.defaults().getMaxFrameBytes());
        List<Throwable> failures = new ArrayList<>();
        Thread parked = new Thread(() -> {
            try {
                reader.hasNext();
            } catch (Throwable thrown) {
                failures.add(thrown);
            }
        });

        parked.start();
        assertTrue(body.reading.await(5, TimeUnit.SECONDS));
        // The close must not queue behind the reader parked in the body: it is what unblocks
        // the reader, and it returns the moment the body is shut.
        assertTimeoutPreemptively(Duration.ofSeconds(5), reader::close);
        parked.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(parked.isAlive());
        assertEquals(1, failures.size());
    }

    /** A body that parks its reader until it is closed, the way a silent provider does. */
    private static class SilentStream extends InputStream {

        private final CountDownLatch reading = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            reading.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            throw new IOException("stream closed");
        }

        @Override
        public void close() {
            released.countDown();
        }
    }

}
