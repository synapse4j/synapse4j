package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatContextTest {

    @Test
    void contextsCompareByIdentityNotContents() {
        ChatContext one = new ChatContext();
        one.setSessionId("s-1");
        ChatContext two = new ChatContext();
        two.setSessionId("s-1");

        // Two contexts naming the same session stay two objects the application holds apart;
        // nothing here sorts, deduplicates or relinks them.
        assertFalse(one.equals(two));
    }

    @Test
    void toStringCarriesTheSessionIdAndTurnButCountsTheEntries() {
        ChatContext context = new ChatContext();
        context.setSessionId("s-1");
        context.setTurn(3);
        context.getAttributes().put("secret", "hunter2");

        String rendered = context.toString();

        assertTrue(rendered.contains("sessionId=s-1"));
        assertTrue(rendered.contains("turn=3"));
        assertTrue(rendered.contains("attributes=1"));
        assertFalse(rendered.contains("hunter2"));
        assertEquals(1, context.getAttributes().size());
    }

}
