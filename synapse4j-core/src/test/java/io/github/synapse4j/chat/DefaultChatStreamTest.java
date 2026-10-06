package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

class DefaultChatStreamTest {

    @Test
    void eventsHandedOrderedAndFolded() {
        List<ChatStreamEvent> arrived = events("one", "two", "three");
        List<String> folded = new ArrayList<>();
        try (ChatStream stream = new DefaultChatStream(arrived.iterator(),
                (response, event) -> folded.add(event.getEventType()), () -> {
                })) {
            List<String> seen = new ArrayList<>();
            for (ChatStreamEvent event : stream) {
                seen.add(event.getEventType());
            }

            assertEquals(List.of("one", "two", "three"), seen);
            assertEquals(List.of("one", "two", "three"), folded);
        }
    }

    @Test
    void aggregatedResponseReflectsConsumed() {
        List<ChatStreamEvent> arrived = events("one", "two", "stop");
        try (ChatStream stream = new DefaultChatStream(arrived.iterator(), DefaultChatStreamTest::foldFinish, () -> {
        })) {
            assertEquals(null, stream.aggregatedResponse().getFinishReason());

            Iterator<ChatStreamEvent> iterator = stream.iterator();
            iterator.next();
            iterator.next();
            assertEquals(null, stream.aggregatedResponse().getFinishReason());

            iterator.next();
            assertEquals("stop", stream.aggregatedResponse().getFinishReason());
        }
    }

    @Test
    void earlyBreakKeepsPartialAggregation() {
        List<ChatStreamEvent> arrived = events("one", "two", "stop");
        try (ChatStream stream = new DefaultChatStream(arrived.iterator(), DefaultChatStreamTest::foldFinish, () -> {
        })) {
            for (ChatStreamEvent first : stream) {
                assertEquals("one", first.getEventType());
                break;
            }

            assertEquals(null, stream.aggregatedResponse().getFinishReason());
        }
    }

    @Test
    void secondIteratorCallFails() {
        try (ChatStream stream = new DefaultChatStream(events("one").iterator(), (response, event) -> {
        }, () -> {
        })) {
            stream.iterator();

            assertThrows(IllegalStateException.class, stream::iterator);
        }
    }

    @Test
    void readingPastEndFollowsContract() {
        try (ChatStream stream = new DefaultChatStream(List.<ChatStreamEvent>of().iterator(), (response, event) -> {
        }, () -> {
        })) {
            Iterator<ChatStreamEvent> iterator = stream.iterator();

            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
        }
    }

    @Test
    void runningToEndReleasesSource() {
        AtomicInteger closed = new AtomicInteger();
        try (ChatStream stream = new DefaultChatStream(events("one", "two").iterator(), (response, event) -> {
        }, closed::incrementAndGet)) {
            List<String> consumed = new ArrayList<>();
            for (ChatStreamEvent event : stream) {
                consumed.add(event.getEventType());
            }

            assertEquals(List.of("one", "two"), consumed);
            assertEquals(1, closed.get());
            stream.close();
            assertEquals(1, closed.get(), "closing after the end must not release a second time");
        }
    }

    @Test
    void closeReleasesOnceShutsIterator() {
        AtomicInteger closed = new AtomicInteger();
        ChatStream stream = new DefaultChatStream(events("one").iterator(), (response, event) -> {
        }, closed::incrementAndGet);
        Iterator<ChatStreamEvent> iterator = stream.iterator();

        stream.close();
        stream.close();

        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, iterator::hasNext);
        assertThrows(IllegalStateException.class, iterator::next);
    }

    @Test
    void failingSourceReleasesAndRethrows() {
        AtomicInteger closed = new AtomicInteger();
        Iterator<ChatStreamEvent> failing = new Iterator<ChatStreamEvent>() {
            @Override
            public boolean hasNext() {
                throw new SynapseException("the source failed");
            }

            @Override
            public ChatStreamEvent next() {
                throw new NoSuchElementException();
            }
        };
        ChatStream stream = new DefaultChatStream(failing, (response, event) -> {
        }, closed::incrementAndGet);
        Iterator<ChatStreamEvent> iterator = stream.iterator();

        SynapseException thrown = assertThrows(SynapseException.class, iterator::hasNext);

        assertEquals("the source failed", thrown.getMessage());
        assertEquals(1, closed.get(), "a failed source leaves nothing to read — release it");
        stream.close();
        assertEquals(1, closed.get(), "closing after the failure must not release a second time");
    }

    @Test
    void readAfterPromiseReleasesConnection() {
        AtomicInteger closed = new AtomicInteger();
        Iterator<ChatStreamEvent> failing = new Iterator<ChatStreamEvent>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public ChatStreamEvent next() {
                throw new SynapseException("the source failed");
            }
        };
        ChatStream stream = new DefaultChatStream(failing, (response, event) -> {
        }, closed::incrementAndGet);
        Iterator<ChatStreamEvent> iterator = stream.iterator();

        // hasNext saying yes and the read that follows failing is the same ending as hasNext
        // failing: the answer is over, and nothing will read the connection again.
        SynapseException thrown = assertThrows(SynapseException.class, iterator::next);

        assertEquals("the source failed", thrown.getMessage());
        assertEquals(1, closed.get(), "a failed source leaves nothing to read — release it");
        stream.close();
        assertEquals(1, closed.get(), "closing after the failure must not release a second time");
    }

    @Test
    void closeFailureUsesSynapseException() {
        ChatStream stream = new DefaultChatStream(events("one").iterator(), (response, event) -> {
        }, () -> {
            throw new IOException("socket refused");
        });

        var thrown = assertThrows(SynapseIOException.class, stream::close);

        assertEquals("Closing the stream failed", thrown.getMessage());
        assertEquals(IOException.class, thrown.getCause().getClass());
    }

    @Test
    void eventPipelineRunsBeforeFold() {
        List<ChatStreamEvent> arrived = events("one", "two");
        List<String> folded = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (ChatStream stream = new DefaultChatStream(arrived.iterator(), event -> {
            event.setEventType(event.getEventType() + "-fixed");
        }, (response, event) -> folded.add(event.getEventType()), () -> {
        })) {
            for (ChatStreamEvent event : stream) {
                seen.add(event.getEventType());
            }
        }

        assertEquals(List.of("one-fixed", "two-fixed"), seen);
        assertEquals(List.of("one-fixed", "two-fixed"), folded);
    }

    private static List<ChatStreamEvent> events(String... types) {
        List<ChatStreamEvent> arrived = new ArrayList<>();
        for (String type : types) {
            ChatStreamEvent event = new ChatStreamEvent();
            event.setEventType(type);
            arrived.add(event);
        }
        return arrived;
    }

    /** The fold a provider ships: the event that names a finish reason ends the aggregation. */
    private static void foldFinish(ChatResponse response, ChatStreamEvent event) {
        if ("stop".equals(event.getEventType())) {
            response.setFinishReason("stop");
        }
    }

}
