package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void newMessageHasNoRoleAndNoParts() {
        ChatMessage message = new ChatMessage();

        assertNull(message.getRole());
        assertTrue(message.getParts().isEmpty());
        assertTrue(message.getExtras().isEmpty());
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
    void partsAreMutableAndCannotBeSetToNull() {
        ChatMessage message = new ChatMessage(ChatRole.USER, new ArrayList<>());

        message.getParts().add(new TextPart("hello"));

        assertEquals(1, message.getParts().size());
        assertThrows(NullPointerException.class, () -> message.setParts(null));
    }

    @Test
    void everyMessageGetsItsOwnExtrasBag() {
        ChatMessage one = new ChatMessage();
        ChatMessage two = new ChatMessage();

        one.getExtras().put("service_tier", "standard");

        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void equalityCoversRolePartsAndExtras() {
        ChatMessage one = new ChatMessage(ChatRole.USER, new ArrayList<>());
        one.getParts().add(new TextPart("hello"));
        ChatMessage two = new ChatMessage(ChatRole.USER, new ArrayList<>());
        two.getParts().add(new TextPart("hello"));

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.setRole(ChatRole.ASSISTANT);
        assertNotEquals(one, two);

        two.setRole(ChatRole.USER);
        two.getParts().add(new TextPart("more"));
        assertNotEquals(one, two);

        two.getParts().remove(1);
        two.getExtras().put("service_tier", "standard");
        assertNotEquals(one, two);
    }

    @Test
    void toStringCoversRolePartsAndExtras() {
        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, new ArrayList<>());
        message.getParts().add(new TextPart("hello"));

        String rendered = message.toString();

        assertTrue(rendered.contains("role=assistant"));
        assertTrue(rendered.contains("text=hello"));
        assertTrue(rendered.contains("extras="));
    }

}
