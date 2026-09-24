package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void newMessageHasNoRoleNoPartsAndNoExtras() {
        ChatMessage message = new ChatMessage();

        assertNull(message.getRole());
        assertTrue(message.getParts().isEmpty());
        assertNull(message.getExtras());
    }

    @Test
    void getOrCreateExtrasBuildsTheBagOnceAndNeverAnswersNull() {
        ChatMessage message = new ChatMessage();

        ProviderExtras created = message.getOrCreateExtras();

        assertNotNull(created);
        assertSame(created, message.getOrCreateExtras());
        assertSame(created, message.getExtras());
    }

    @Test
    void roleConstantsCarryTheProtocolNeutralValues() {
        assertEquals("system", ChatRole.SYSTEM);
        assertEquals("user", ChatRole.USER);
        assertEquals("assistant", ChatRole.ASSISTANT);
        assertEquals("tool", ChatRole.TOOL);
    }

    @Test
    void roleAcceptsAnyValue() {
        ChatMessage message = new ChatMessage();

        message.setRole("whisper");

        assertEquals("whisper", message.getRole());
    }

    @Test
    void partsAreMutable() {
        ChatMessage message = new ChatMessage(ChatRole.USER);

        message.addPart(new TextPart("hello"));

        assertEquals(1, message.getParts().size());
    }

    @Test
    void everyMessageGetsItsOwnExtrasBag() {
        ChatMessage one = new ChatMessage();
        ChatMessage two = new ChatMessage();

        one.setExtras(new ProviderExtras().put("service_tier", "standard"));

        assertNull(two.getExtras());
    }

    @Test
    void toStringCoversRolePartsAndExtras() {
        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT);
        message.addPart(new TextPart("hello"));

        String rendered = message.toString();

        assertTrue(rendered.contains("role=assistant"));
        assertTrue(rendered.contains("parts=1"));
        assertTrue(rendered.contains("extras="));
    }

}
