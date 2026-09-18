package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

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
    void messagesAndToolsAreMutableAndCannotBeSetToNull() {
        ChatRequest request = new ChatRequest();

        request.getMessages().add(new ChatMessage(ChatRole.USER, new ArrayList<>()));
        request.getTools().add(new ToolDefinition("get_weather", "Looks up the weather", "{}"));

        assertEquals(1, request.getMessages().size());
        assertEquals(1, request.getTools().size());
        assertThrows(NullPointerException.class, () -> request.setMessages(null));
        assertThrows(NullPointerException.class, () -> request.setTools(null));
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
    void theAllArgumentsConstructorCarriesEverything() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatRole.USER, new ArrayList<>()));
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("get_weather", "Looks up the weather", "{}"));
        ChatResponseFormat responseFormat = new ChatResponseFormat();
        responseFormat.setType(ChatResponseFormat.TYPE_JSON);
        ChatOptions options = new ChatOptions();
        options.setModel("gpt-4o");

        ChatRequest request = new ChatRequest(messages, tools, responseFormat, options);

        assertSame(messages, request.getMessages());
        assertSame(tools, request.getTools());
        assertSame(responseFormat, request.getResponseFormat());
        assertSame(options, request.getOptions());
    }

    @Test
    void equalityAndHashCodeCoverEveryField() {
        ChatRequest one = new ChatRequest();
        ChatRequest two = new ChatRequest();

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.getMessages().add(new ChatMessage(ChatRole.USER, new ArrayList<>()));
        assertNotEquals(one, two);
        two.getMessages().clear();

        two.getTools().add(new ToolDefinition("get_weather", "Looks up the weather", "{}"));
        assertNotEquals(one, two);
        two.getTools().clear();

        two.getOptions().setModel("gpt-4o");
        assertNotEquals(one, two);
        two.getOptions().setModel(null);

        two.getResponseFormat().setType(ChatResponseFormat.TYPE_JSON);
        assertNotEquals(one, two);
    }

    @Test
    void toStringMentionsTheFields() {
        String rendered = new ChatRequest().toString();

        assertTrue(rendered.contains("messages="));
        assertTrue(rendered.contains("tools="));
        assertTrue(rendered.contains("options="));
    }

}
