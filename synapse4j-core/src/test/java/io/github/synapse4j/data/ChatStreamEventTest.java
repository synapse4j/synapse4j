package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatStreamEventTest {

    @Test
    void startsWithEverythingUnsetButExtras() {
        ChatStreamEvent event = new ChatStreamEvent();
        event.setEventType("content_block_delta");

        assertEquals("content_block_delta", event.getEventType());
        assertNull(event.getDelta());
        assertNull(event.getFinishReason());
        assertNull(event.getUsage());
        assertNull(event.getId());
        assertNull(event.getModel());
        assertNotNull(event.getExtras());
    }

    @Test
    void theEventTypeIsRequired() {
        ChatStreamEvent event = new ChatStreamEvent();

        assertThrows(NullPointerException.class, () -> event.setEventType(null));
        assertThrows(NullPointerException.class, () -> new ChatStreamEvent(null, null, null, null, null, null));
    }

}
