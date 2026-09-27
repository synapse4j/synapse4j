package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void continuingTakesTheContextTheExchangeRanOn() {
        ChatContext context = new ChatContext();
        context.setSessionId("session-1");
        ChatResponse answer = new ChatResponse();
        answer.setMessage(new ChatMessage(ChatRole.ASSISTANT).addText("hello"));
        answer.setContext(context);

        ChatRequest request = new ChatRequest().addMessage(new ChatMessage(ChatRole.USER).addText("hi"));
        request.continueWith(answer);

        assertEquals(2, request.getMessages().size());
        // The exchange's own context is the one the conversation goes on with, since that is where
        // an adopted session id and the turn live.
        assertSame(context, request.getContext());
    }

}
