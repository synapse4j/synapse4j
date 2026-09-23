package io.github.synapse4j.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SynapseHttpExceptionTest {

    @Test
    void itCarriesTheStatusAlongsideTheMessage() {
        SynapseHttpException thrown = new SynapseHttpException("OpenAI request failed with HTTP 429", 429);

        assertEquals(429, thrown.getStatusCode());
        assertEquals("OpenAI request failed with HTTP 429", thrown.getMessage());
    }

    @Test
    void itIsCaughtThroughTheBaseLikeEveryOtherFailure() {
        SynapseException thrown = assertThrows(SynapseException.class,
                () -> {
                    throw new SynapseHttpException("HTTP 502", 502);
                });

        assertEquals(502, assertInstanceOf(SynapseHttpException.class, thrown).getStatusCode());
    }

}
