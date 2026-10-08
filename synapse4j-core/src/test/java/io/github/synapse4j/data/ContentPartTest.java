package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContentPartTest {

    @Test
    void extrasFrozenOnConstruction() {
        ProviderExtras extras = new ProviderExtras().put("vendor", "x");

        TextPart part = new TextPart("hello", extras);
        extras.put("vendor", "y");

        assertEquals("x", part.getExtras().get("vendor"));
    }

}
