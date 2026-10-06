package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatRequest;

class DefaultSystemMessageCustomizerTest {

    @Test
    void standingMessageFillsOnlyGaps() {
        AbstractChatClientTest.StubChatClient client = new AbstractChatClientTest.StubChatClient();
        client.addChatCustomizer(new DefaultSystemMessageCustomizer("You are terse."));

        // A request that states its own framing keeps it — the standing one fills a gap, it does not
        // overwrite a caller — while a request that states none is given the standing one.
        client.chat(new ChatRequest().systemMessage("Speak French."));
        assertEquals("Speak French.", client.seen.getSystemMessage().getText());

        client.chat(new ChatRequest());
        assertEquals("You are terse.", client.seen.getSystemMessage().getText());
    }

}
