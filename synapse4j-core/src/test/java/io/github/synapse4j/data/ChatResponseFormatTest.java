package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponseFormatTest {

    @Test
    void everyFormatGetsItsOwnBag() {
        ChatResponseFormat one = new ChatResponseFormat();
        ChatResponseFormat two = new ChatResponseFormat();

        one.getExtras().put("strict", true);

        assertTrue(two.getExtras().isEmpty());
    }

}
