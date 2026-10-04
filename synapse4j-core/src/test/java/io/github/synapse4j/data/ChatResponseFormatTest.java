package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import org.junit.jupiter.api.Test;

class ChatResponseFormatTest {

    @Test
    void effectiveFillsEveryGapFromTheDefaultAndLetsTheCallWin() {
        ChatResponseFormat call = new ChatResponseFormat();
        call.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        call.getExtras().put("strict", true);

        JsonSchema schema = new JsonSchemaBuilder().setType("object").build();
        ChatResponseFormat defaults = new ChatResponseFormat();
        defaults.setType(ChatResponseFormat.TYPE_JSON);
        defaults.setName("answer");
        defaults.setSchema(schema);
        defaults.getExtras().put("strict", false);

        ChatResponseFormat effective = ChatResponseFormat.effective(call, defaults);

        assertEquals(ChatResponseFormat.TYPE_JSON_SCHEMA, effective.getType());
        assertEquals("answer", effective.getName());
        assertSame(schema, effective.getSchema());
        assertEquals(Boolean.TRUE, effective.getExtras().get("strict"));
        // The merge answers a new instance; neither side is changed.
        assertNull(call.getName());
        assertEquals(ChatResponseFormat.TYPE_JSON, defaults.getType());
    }

    @Test
    void everyFormatGetsItsOwnBag() {
        ChatResponseFormat one = new ChatResponseFormat();
        ChatResponseFormat two = new ChatResponseFormat();

        one.getExtras().put("strict", true);

        assertTrue(two.getExtras().isEmpty());
    }

}
