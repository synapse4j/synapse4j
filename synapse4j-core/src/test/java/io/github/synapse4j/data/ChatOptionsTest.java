package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatOptionsTest {

    @Test
    void responseFormatCannotBeSetToNull() {
        ChatOptions options = new ChatOptions();

        assertThrows(NullPointerException.class, () -> options.setResponseFormat(null));
    }

    @Test
    void everyOptionsGetsItsOwnBags() {
        ChatOptions one = new ChatOptions();
        ChatOptions two = new ChatOptions();

        one.getHeaders().put("openai-beta", "responses=v1");
        one.getExtras().put("service_tier", "flex");

        assertTrue(two.getHeaders().isEmpty());
        assertTrue(two.getExtras().isEmpty());
    }

}
