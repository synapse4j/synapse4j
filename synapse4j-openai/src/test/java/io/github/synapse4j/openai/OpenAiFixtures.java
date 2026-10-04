package io.github.synapse4j.openai;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.TextPart;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * What the two OpenAI client tests build a request with or read out of an answer, shared rather
 * than copied per protocol: a copy that drifts between them would describe neither endpoint. What
 * only one protocol's test needs stays with it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class OpenAiFixtures {

    /** A request with a model set, which is all a request-writing test needs. */
    static ChatRequest requestWithModel() {
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        return request;
    }

    /** The text one event contributes, or {@code null} when it contributes none. */
    static String textOf(ChatStreamEvent event) {
        if (event.getDelta() == null || event.getDelta().getParts().isEmpty()) {
            return null;
        }
        return ((TextPart) event.getDelta().getParts().get(0)).getText();
    }

    /** Fails on a wire document that carried a member serialized as null. */
    static void assertNoNullValues(Map<String, Object> map) {
        map.forEach((key, value) -> assertNotNull(value, "wire field '" + key + "' was serialized as null"));
    }

}
