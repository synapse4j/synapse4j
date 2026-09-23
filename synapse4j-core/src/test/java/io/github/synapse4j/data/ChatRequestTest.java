package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void newRequestIsEmptyButNeverNull() {
        ChatRequest request = new ChatRequest();

        assertTrue(request.getMessages().isEmpty());
        assertTrue(request.getTools().isEmpty());
        assertNull(request.getResponseFormat().getType());
        assertNull(request.getOptions().getModel());
        assertNull(request.getOptions().getTemperature());
    }

    @Test
    void messagesAndToolsAreMutable() {
        ChatRequest request = new ChatRequest();

        request.addMessage(new ChatMessage(ChatRole.USER));
        request.addTool(new ToolDefinition("get_weather", "Looks up the weather", "{}"));

        assertEquals(1, request.getMessages().size());
        assertEquals(1, request.getTools().size());
    }

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
    void toStringMentionsTheFields() {
        String rendered = new ChatRequest().toString();

        assertTrue(rendered.contains("messages="));
        assertTrue(rendered.contains("tools="));
        assertTrue(rendered.contains("options="));
    }

}
