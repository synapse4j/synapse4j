package io.github.synapse4j.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenAiConfigTest {

    @Test
    void theApiKeyNeverRidesInToString() {
        OpenAiConfig config = new OpenAiConfig();
        config.setApiKey("sk-secret");

        // The decision, not the generator: a credential belongs in the header it authenticates,
        // and nowhere a log line or an exception message might carry it.
        assertFalse(config.toString().contains("sk-secret"));
        assertTrue(config.toString().contains("api.openai.com"));
    }

}
