package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponseTest {

    @Test
    void newResponseHasAnEmptyMessageAndNothingElse() {
        ChatResponse response = new ChatResponse();

        assertNull(response.getMessage().getRole());
        assertTrue(response.getMessage().getParts().isEmpty());
        assertNull(response.getFinishReason());
        assertNull(response.getUsage());
        assertNull(response.getModel());
        assertNull(response.getId());
        assertTrue(response.getHeaders().isEmpty());
        assertTrue(response.getExtras().isEmpty());
    }

    @Test
    void messageCannotBeSetToNull() {
        ChatResponse response = new ChatResponse();

        assertThrows(NullPointerException.class, () -> response.setMessage(null));
    }

    @Test
    void settersCarryEveryField() {
        ChatResponse response = new ChatResponse();
        Usage usage = new Usage();
        usage.setInputTokens(120);
        usage.setOutputTokens(48);

        response.getMessage().setRole(ChatRole.ASSISTANT);
        response.getMessage().getParts().add(new TextPart("hello"));
        response.setFinishReason(ChatFinishReason.TOOL_CALLS);
        response.setUsage(usage);
        response.setModel("gpt-4o-2024-08-06");
        response.setId("chatcmpl-1");

        assertEquals(ChatRole.ASSISTANT, response.getMessage().getRole());
        assertEquals("hello", ((TextPart) response.getMessage().getParts().get(0)).getText());
        assertEquals(ChatFinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(120, response.getUsage().getInputTokens());
        assertEquals(48, response.getUsage().getOutputTokens());
        assertEquals("gpt-4o-2024-08-06", response.getModel());
        assertEquals("chatcmpl-1", response.getId());
    }

    @Test
    void everyResponseGetsItsOwnBags() {
        ChatResponse one = new ChatResponse();
        ChatResponse two = new ChatResponse();

        one.getMessage().getExtras().put("cache_control", "ephemeral");
        one.getExtras().put("service_tier", "flex");

        assertTrue(two.getMessage().getExtras().isEmpty());
        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void equalityAndHashCodeCoverEveryField() {
        ChatResponse one = new ChatResponse();
        ChatResponse two = new ChatResponse();

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.getMessage().getParts().add(new TextPart("hello"));
        assertNotEquals(one, two);
        two.setMessage(new ChatMessage());

        two.setFinishReason(ChatFinishReason.STOP);
        assertNotEquals(one, two);
        two.setFinishReason(null);

        Usage usage = new Usage();
        usage.setOutputTokens(1);
        two.setUsage(usage);
        assertNotEquals(one, two);
        two.setUsage(null);

        two.setModel("gpt-4o");
        assertNotEquals(one, two);
        two.setModel(null);

        two.setId("chatcmpl-1");
        assertNotEquals(one, two);
        two.setId(null);

        two.getExtras().put("service_tier", "flex");
        assertNotEquals(one, two);
        two.getExtras().remove("service_tier");

        two.getHeaders().put("x-request-id", "abc");
        assertNotEquals(one, two);
    }

    @Test
    void finishReasonConstantsCarryTheNeutralValues() {
        assertEquals("stop", ChatFinishReason.STOP);
        assertEquals("length", ChatFinishReason.LENGTH);
        assertEquals("tool_calls", ChatFinishReason.TOOL_CALLS);
        assertEquals("content_filter", ChatFinishReason.CONTENT_FILTER);
    }

    @Test
    void toStringMentionsTheFields() {
        ChatResponse response = new ChatResponse();
        response.setId("chatcmpl-1");

        String rendered = response.toString();

        assertTrue(rendered.contains("id=chatcmpl-1"));
        assertTrue(rendered.contains("message="));
        assertTrue(rendered.contains("extras="));
    }

}
