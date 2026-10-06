package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ContentPartTest {

    @Test
    void extrasNeverNullAndCached() {
        TextPart part = new TextPart("hello");

        ProviderExtras created = part.getOrCreateExtras();

        assertNotNull(created);
        assertSame(created, part.getOrCreateExtras());
        assertSame(created, part.getExtras());
    }

    @Test
    void copyIndependentWithExtras() {
        TextPart part = new TextPart("hello");
        part.getOrCreateExtras().put("vendor", "x");

        TextPart copy = part.copy();

        assertNotSame(part, copy);
        assertEquals("hello", copy.getText());
        assertEquals("x", copy.getExtras().get("vendor"));
    }

}
