package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void joinsTextPartsInOrder() {
        ChatMessage message = ChatMessage.of(ChatRole.ASSISTANT, new TextPart("Hello"),
                new ReasoningPart("weigh it up"), new TextPart(null), new TextPart(", world"));

        assertEquals("Hello, world", message.getText());
    }

    @Test
    void textEmptyWithoutTextParts() {
        assertEquals("", new ChatMessage(null, null).getText());
        assertEquals("", ChatMessage.of(ChatRole.ASSISTANT, new ReasoningPart("hmm")).getText());
    }

    @Test
    void copiesPartsItIsGiven() {
        List<ContentPart> parts = new ArrayList<>(List.of(new TextPart("hello")));

        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, null, parts);
        parts.clear();

        assertEquals("hello", message.getText());
    }

    @Test
    void builderCollectsParts() {
        ChatMessage message = ChatMessage.builder()
                .role(ChatRole.ASSISTANT)
                .part(new TextPart("Hello"))
                .part(new TextPart(", world"))
                .build();

        assertEquals("Hello, world", message.getText());
    }

    @Test
    void partsAreUnmodifiable() {
        ChatMessage message = ChatMessage.user("hello");

        assertThrows(UnsupportedOperationException.class, () -> message.getParts().add(new TextPart("x")));
    }

    @Test
    void toBuilderKeepsExtrasFrozen() {
        ChatMessage base = ChatMessage.builder()
                .role(ChatRole.USER)
                .extras(new ProviderExtras().put("vendor", "x"))
                .build();

        ChatMessage derived = base.toBuilder().part(new TextPart("hello")).build();

        assertThrows(UnsupportedOperationException.class, () -> derived.getExtras().put("vendor", "y"));
    }

    @Test
    void mapExtrasFillsEmptyBag() {
        ChatMessage message = ChatMessage.builder()
                .role(ChatRole.USER)
                .mapExtras(bag -> bag.put("vendor", "x"))
                .build();

        assertEquals("x", message.getExtras().get("vendor"));
    }

    @Test
    void mapExtrasExtendsCarriedBag() {
        ChatMessage base = ChatMessage.builder()
                .role(ChatRole.USER)
                .extras(new ProviderExtras().put("vendor", "x"))
                .build();

        ChatMessage derived = base.toBuilder().mapExtras(bag -> bag.put("flag", "on")).build();

        assertEquals("x", derived.getExtras().get("vendor"));
        assertEquals("on", derived.getExtras().get("flag"));
    }

}
