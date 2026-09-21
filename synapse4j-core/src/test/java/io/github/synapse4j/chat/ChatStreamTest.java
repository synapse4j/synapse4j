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
import io.github.synapse4j.exception.SynapseIOException;

class ChatStreamTest {

    @Test
    void handsOutEventsInOrderAndFoldsEachConsumedOne() {
        List<ChatStreamEvent> arrived = events("one", "two", "three");
        List<String> folded = new ArrayList<>();
        ChatStream stream = new ChatStream(arrived.iterator(), (response, event) -> folded.add(event.getEventType()),
                () -> {
                });

        List<String> seen = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            seen.add(event.getEventType());
        }

        assertEquals(List.of("one", "two", "three"), seen);
        assertEquals(List.of("one", "two", "three"), folded);
    }

    @Test
    void aggregatedResponseReflectsOnlyWhatWasConsumed() {
        List<ChatStreamEvent> arrived = events("one", "two", "stop");
        ChatStream stream = new ChatStream(arrived.iterator(), ChatStreamTest::foldFinish, () -> {
        });

        assertEquals(null, stream.aggregatedResponse().getFinishReason());

        Iterator<ChatStreamEvent> iterator = stream.iterator();
        iterator.next();
        iterator.next();
        assertEquals(null, stream.aggregatedResponse().getFinishReason());

        iterator.next();
        assertEquals("stop", stream.aggregatedResponse().getFinishReason());
    }

    @Test
    void breakingOutEarlyKeepsThePartialAggregation() {
        List<ChatStreamEvent> arrived = events("one", "two", "stop");
        ChatStream stream = new ChatStream(arrived.iterator(), ChatStreamTest::foldFinish, () -> {
        });

        for (ChatStreamEvent ignored : stream) {
            break;
        }

        assertEquals(null, stream.aggregatedResponse().getFinishReason());
    }

    @Test
    void aSecondIteratorCallFailsBecauseNothingIsBuffered() {
        ChatStream stream = new ChatStream(events("one").iterator(), (response, event) -> {
        }, () -> {
        });
        stream.iterator();

        assertThrows(IllegalStateException.class, stream::iterator);
    }

    @Test
    void readingPastTheEndFollowsIteratorContract() {
        ChatStream stream = new ChatStream(List.<ChatStreamEvent>of().iterator(), (response, event) -> {
        }, () -> {
        });
        Iterator<ChatStreamEvent> iterator = stream.iterator();

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void closingReleasesOnceAndShutsTheIterator() {
        AtomicInteger closed = new AtomicInteger();
        ChatStream stream = new ChatStream(events("one").iterator(), (response, event) -> {
        }, closed::incrementAndGet);
        Iterator<ChatStreamEvent> iterator = stream.iterator();

        stream.close();
        stream.close();

        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, iterator::hasNext);
        assertThrows(IllegalStateException.class, iterator::next);
    }

    @Test
    void aCloseFailureIsReportedAsTheLibraryOwns() {
        ChatStream stream = new ChatStream(events("one").iterator(), (response, event) -> {
        }, () -> {
            throw new IOException("socket refused");
        });

        var thrown = assertThrows(SynapseIOException.class, stream::close);

        assertEquals("Closing the stream failed", thrown.getMessage());
        assertEquals(IOException.class, thrown.getCause().getClass());
    }

    @Test
    void constructorRejectsMissingPieces() {
        Iterator<ChatStreamEvent> source = events("one").iterator();

        assertThrows(NullPointerException.class, () -> new ChatStream(null, (r, e) -> {
        }, () -> {
        }));
        assertThrows(NullPointerException.class, () -> new ChatStream(source, null, () -> {
        }));
        assertThrows(NullPointerException.class, () -> new ChatStream(source, (r, e) -> {
        }, null));
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
