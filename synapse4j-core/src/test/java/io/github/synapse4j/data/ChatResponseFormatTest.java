package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatResponseFormatTest {

    @Test
    void newFormatAsksForNothing() {
        ChatResponseFormat format = new ChatResponseFormat();

        assertNull(format.getType());
        assertNull(format.getName());
        assertNull(format.getDescription());
        assertNull(format.getSchema());
        assertTrue(format.getExtras().isEmpty());
    }

    @Test
    void settersCarryEveryField() {
        ChatResponseFormat format = new ChatResponseFormat();

        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("weather");
        format.setDescription("The weather for a place");
        format.setSchema("{\"type\":\"object\"}");

        assertEquals(ChatResponseFormat.TYPE_JSON_SCHEMA, format.getType());
        assertEquals("weather", format.getName());
        assertEquals("The weather for a place", format.getDescription());
        assertEquals("{\"type\":\"object\"}", format.getSchema());
    }

    @Test
    void typeConstantsCarryTheWireValues() {
        assertEquals("text", ChatResponseFormat.TYPE_TEXT);
        assertEquals("json", ChatResponseFormat.TYPE_JSON);
        assertEquals("json_schema", ChatResponseFormat.TYPE_JSON_SCHEMA);
    }

    @Test
    void extrasCarryProviderSpecificFields() {
        ChatResponseFormat format = new ChatResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);

        format.getExtras().put("strict", true);

        assertEquals(true, format.getExtras().get("strict"));
    }

    @Test
    void everyFormatGetsItsOwnBag() {
        ChatResponseFormat one = new ChatResponseFormat();
        ChatResponseFormat two = new ChatResponseFormat();

        one.getExtras().put("strict", true);

        assertTrue(two.getExtras().isEmpty());
    }

    @Test
    void equalityAndHashCodeCoverEveryField() {
        ChatResponseFormat one = new ChatResponseFormat();
        ChatResponseFormat two = new ChatResponseFormat();

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());

        two.setType(ChatResponseFormat.TYPE_JSON);
        assertNotEquals(one, two);
        two.setType(null);

        two.setName("weather");
        assertNotEquals(one, two);
        two.setName(null);

        two.setDescription("The weather for a place");
        assertNotEquals(one, two);
        two.setDescription(null);

        two.setSchema("{}");
        assertNotEquals(one, two);
        two.setSchema(null);

        two.getExtras().put("strict", true);
        assertNotEquals(one, two);
    }

    @Test
    void toStringMentionsTheFields() {
        ChatResponseFormat format = new ChatResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON);

        String rendered = format.toString();

        assertTrue(rendered.contains("type=json"));
        assertTrue(rendered.contains("extras="));
    }

}
