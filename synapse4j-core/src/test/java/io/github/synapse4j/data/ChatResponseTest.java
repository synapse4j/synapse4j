package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChatResponseTest {

    @Test
    void responseBagsNotShared() {
        ChatResponse one = new ChatResponse();
        ChatResponse two = new ChatResponse();

        one.setMessage(new ChatMessage(null, null, List.of(),
                new ProviderExtras().put("cache_control", "ephemeral")));
        one.getExtras().put("service_tier", "flex");

        assertNull(two.getMessage().getExtras());
        assertTrue(two.getExtras().isEmpty());
    }

}
