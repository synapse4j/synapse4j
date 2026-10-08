package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;

class ChatRequestTest {

    @Test
    void requestOptionsNotShared() {
        ChatRequest one = new ChatRequest();
        ChatRequest two = new ChatRequest();

        one.getOptions().setTemperature(0.7);

        assertNull(two.getOptions().getTemperature());
    }

    @Test
    void copyLeavesOriginalAlone() {
        ChatRequest original = new ChatRequest();
        original.addPendingMessage(ChatMessage.user("hi"));
        original.addHistoryMessage(ChatMessage.user("earlier"));
        original.addTool(tool("a"));
        original.getOptions().setTemperature(0.7);
        ChatContext context = new ChatContext();
        original.setContext(context);

        ChatRequest copy = new ChatRequest(original);

        // The same messages in the copy's own lists; the context is shared.
        assertEquals(original.getHistoryMessages(), copy.getHistoryMessages());
        assertEquals(original.getPendingMessages(), copy.getPendingMessages());
        assertNotSame(original.getHistoryMessages(), copy.getHistoryMessages());
        assertNotSame(original.getPendingMessages(), copy.getPendingMessages());
        assertNotSame(original.getTools(), copy.getTools());
        assertNotSame(original.getOptions(), copy.getOptions());
        assertSame(context, copy.getContext());

        // Nothing the copy does reaches back into the original.
        copy.addPendingMessage(ChatMessage.user("more"));
        copy.getTools().clear();
        copy.getOptions().setTemperature(0.1);
        assertEquals(1, original.getPendingMessages().size());
        assertEquals(1, original.getTools().size());
        assertEquals(Double.valueOf(0.7), original.getOptions().getTemperature());
    }

    private static Tool tool(String name) {
        return new Tool() {

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, null, null);
            }

            @Override
            public List<ContentPart> execute(String arguments, ChatContext context) {
                return List.of();
            }
        };
    }

}
