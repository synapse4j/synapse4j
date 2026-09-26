package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void responseFormatAndOptionsCannotBeSetToNull() {
        ChatRequest request = new ChatRequest();

        assertThrows(NullPointerException.class, () -> request.setResponseFormat(null));
        assertThrows(NullPointerException.class, () -> request.setOptions(null));
    }

    @Test
    void everyRequestGetsItsOwnOptions() {
        ChatRequest one = new ChatRequest();
        ChatRequest two = new ChatRequest();

        one.getOptions().setTemperature(0.7);

        assertNull(two.getOptions().getTemperature());
    }

}
