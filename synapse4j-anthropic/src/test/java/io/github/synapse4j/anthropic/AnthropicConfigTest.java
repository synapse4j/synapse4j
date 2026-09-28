package io.github.synapse4j.anthropic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnthropicConfigTest {

    @Test
    void theApiKeyNeverRidesInToString() {
        AnthropicConfig config = new AnthropicConfig();
        config.setApiKey("sk-ant-secret");

        // The decision, not the generator: a credential belongs in the header it authenticates,
        // and nowhere a log line or an exception message might carry it.
        assertFalse(config.toString().contains("sk-ant-secret"));
        assertTrue(config.toString().contains("api.anthropic.com"));
    }

}
