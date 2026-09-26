package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ContentPartTest {

    @Test
    void everyPartStartsWithNoExtrasBag() {
        TextPart part = new TextPart("hello");

        assertNull(part.getExtras());
    }

    @Test
    void getOrCreateExtrasBuildsTheBagOnceAndNeverAnswersNull() {
        TextPart part = new TextPart("hello");

        ProviderExtras created = part.getOrCreateExtras();

        assertNotNull(created);
        assertSame(created, part.getOrCreateExtras());
        assertSame(created, part.getExtras());
    }

    @Test
    void everyPartGetsItsOwnExtrasBag() {
        TextPart one = new TextPart("hello");
        TextPart two = new TextPart("hello");

        one.setExtras(new ProviderExtras().put("temperature", 0.5));

        assertNull(two.getExtras());
    }

}
