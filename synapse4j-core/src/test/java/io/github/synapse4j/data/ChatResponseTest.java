package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponseTest {

    @Test
    void messageCannotBeSetToNull() {
        ChatResponse response = new ChatResponse();

        assertThrows(NullPointerException.class, () -> response.setMessage(null));
    }

    @Test
    void everyResponseGetsItsOwnBags() {
        ChatResponse one = new ChatResponse();
        ChatResponse two = new ChatResponse();

        one.getMessage().setExtras(new ProviderExtras().put("cache_control", "ephemeral"));
        one.getExtras().put("service_tier", "flex");

        assertNull(two.getMessage().getExtras());
        assertTrue(two.getExtras().isEmpty());
    }

}
