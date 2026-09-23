package io.github.synapse4j.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatOptionsTest {

    @Test
    void newOptionsHasNoOpinionAboutAnything() {
        ChatOptions options = new ChatOptions();

        assertNull(options.getModel());
        assertNull(options.getTemperature());
        assertNull(options.getMaxOutputTokens());
        assertNull(options.getTopP());
        assertTrue(options.getHeaders().isEmpty());
        assertTrue(options.getExtras().isEmpty());
    }

    @Test
    void settersCarryEveryKnob() {
        ChatOptions options = new ChatOptions();

        options.setModel("gpt-4o");
        options.setTemperature(0.2);
        options.setMaxOutputTokens(512);
        options.setTopP(0.9);

        assertEquals("gpt-4o", options.getModel());
        assertEquals(0.2, options.getTemperature());
        assertEquals(512, options.getMaxOutputTokens());
        assertEquals(0.9, options.getTopP());
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

    @Test
    void toStringMentionsTheFieldsAndTheBags() {
        ChatOptions options = new ChatOptions();
        options.setModel("gpt-4o");

        String rendered = options.toString();

        assertTrue(rendered.contains("model=gpt-4o"));
        assertTrue(rendered.contains("headers="));
        assertTrue(rendered.contains("extras="));
    }

    @Test
    void httpOptionsIsAbsentUntilAssociated() {
        ChatOptions options = new ChatOptions();

        assertNull(options.getHttpOptions());

        io.github.synapse4j.http.HttpOptions http = new io.github.synapse4j.http.HttpOptions();
        http.setMaxFrameBytes(4096);
        options.setHttpOptions(http);

        assertEquals(http, options.getHttpOptions());
    }

}
