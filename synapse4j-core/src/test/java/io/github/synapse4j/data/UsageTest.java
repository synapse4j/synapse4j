package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UsageTest {

    @Test
    void everyUsageGetsItsOwnBag() {
        Usage one = new Usage();
        Usage two = new Usage();

        one.getExtras().put("cache_creation_input_tokens", 2008);

        assertTrue(two.getExtras().isEmpty());
    }

}
