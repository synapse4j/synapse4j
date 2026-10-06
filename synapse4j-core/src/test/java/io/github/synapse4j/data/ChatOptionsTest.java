package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;

class ChatOptionsTest {

    @Test
    void effectiveFillsEveryGapFromTheDefaultAndLetsTheCallWin() {
        ChatOptions call = new ChatOptions();
        call.setTemperature(0.2);
        call.getHeaders().put("x-shared", "call");
        call.getResponseFormat().setType(ChatResponseFormat.TYPE_JSON_SCHEMA);

        ChatOptions defaults = new ChatOptions();
        defaults.setModel("gpt-4o");
        defaults.setTemperature(1.0);
        defaults.getHeaders().put("x-shared", "default");
        defaults.getHeaders().put("x-default", "1");
        defaults.getExtras().put("service_tier", "flex");
        JsonSchema schema = new JsonSchemaBuilder().setType("object").build();
        defaults.getResponseFormat().setSchema(schema);

        ChatOptions effective = ChatOptions.effective(call, defaults);

        assertEquals("gpt-4o", effective.getModel());
        assertEquals(Double.valueOf(0.2), effective.getTemperature());
        // The bag merges by key, the call's entry winning where both name one.
        assertEquals("call", effective.getHeaders().get("x-shared"));
        assertEquals("1", effective.getHeaders().get("x-default"));
        assertEquals("flex", effective.getExtras().get("service_tier"));
        // The nested format merges the same way: the call's mode, the default's schema.
        assertEquals(ChatResponseFormat.TYPE_JSON_SCHEMA, effective.getResponseFormat().getType());
        assertSame(schema, effective.getResponseFormat().getSchema());
        // The merge answers a new instance; neither side is changed.
        assertNull(call.getModel());
        assertEquals(Double.valueOf(1.0), defaults.getTemperature());
    }

    @Test
    void everyOptionsGetsItsOwnBags() {
        ChatOptions one = new ChatOptions();
        ChatOptions two = new ChatOptions();

        one.getHeaders().put("openai-beta", "responses=v1");
        one.getExtras().put("service_tier", "flex");

        assertTrue(two.getHeaders().isEmpty());
        assertTrue(two.getExtras().isEmpty());
    }

}
