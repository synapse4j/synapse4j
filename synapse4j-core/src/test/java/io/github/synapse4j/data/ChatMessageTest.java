package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void extrasNeverNullAndCached() {
        ChatMessage message = new ChatMessage();

        ProviderExtras created = message.getOrCreateExtras();

        assertNotNull(created);
        assertSame(created, message.getOrCreateExtras());
        assertSame(created, message.getExtras());
    }

    @Test
    void joinsTextPartsInOrder() {
        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT)
                .addText("Hello")
                .addPart(new ReasoningPart("weigh it up"))
                .addPart(new TextPart())
                .addText(", world");

        assertEquals("Hello, world", message.getText());
    }

    @Test
    void textEmptyWithoutTextParts() {
        assertEquals("", new ChatMessage().getText());
        assertEquals("", new ChatMessage(ChatRole.ASSISTANT).addPart(new ReasoningPart("hmm")).getText());
    }

}
