package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class ContentPartTest {

    @Test
    void everyPartStartsWithNoExtrasBag() {
        TextPart part = new TextPart("hello");

        assertNull(part.getExtras());
    }

    @Test
    void everyPartGetsItsOwnExtrasBag() {
        TextPart one = new TextPart("hello");
        TextPart two = new TextPart("hello");

        one.setExtras(new ProviderExtras().put("temperature", 0.5));

        assertNull(two.getExtras());
    }

    @Test
    void extrasAreMutableThroughTheGetter() {
        TextPart part = new TextPart("hello");
        part.setExtras(new ProviderExtras().put("temperature", 0.5));

        assertSame(part.getExtras(), part.getExtras());

        assertEquals(0.5, part.getExtras().get("temperature"));
    }

    @Test
    void toolResultPartsStartEmptyAndAreMutable() {
        ToolResultPart result = new ToolResultPart();

        assertTrue(result.getParts().isEmpty());
        assertFalse(result.isError());

        result.getParts().add(new TextPart("sunny"));

        assertEquals(1, result.getParts().size());
    }

    @Test
    void toolResultPartsCannotBeSetToNull() {
        ToolResultPart result = new ToolResultPart();

        assertThrows(NullPointerException.class, () -> result.setParts(null));
        assertThrows(NullPointerException.class,
                () -> new ToolResultPart("call-1", "weather", null, false));
    }

    @Test
    void toolResultPartsKeepTheListTheyWereGiven() {
        ArrayList<ContentPart> parts = new ArrayList<>();
        parts.add(new TextPart("sunny"));

        ToolResultPart result = new ToolResultPart("call-1", "weather", parts, false);

        assertEquals("call-1", result.getCallId());
        assertEquals("weather", result.getName());
        assertEquals(parts, result.getParts());
    }

    @Test
    void toStringCoversBothTheSubclassAndTheInheritedFields() {
        String rendered = new ToolCallPart("call-1", "get_weather", "{}").toString();

        assertTrue(rendered.contains("callId=call-1"));
        assertTrue(rendered.contains("name=get_weather"));
        assertTrue(rendered.contains("extras="));
    }

}
