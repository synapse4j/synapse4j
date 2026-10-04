package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ContentPartTest {

    @Test
    void getOrCreateExtrasBuildsTheBagOnceAndNeverAnswersNull() {
        TextPart part = new TextPart("hello");

        ProviderExtras created = part.getOrCreateExtras();

        assertNotNull(created);
        assertSame(created, part.getOrCreateExtras());
        assertSame(created, part.getExtras());
    }

}
