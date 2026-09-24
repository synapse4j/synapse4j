package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ToolCallPart;

class ErrorHandlersTest {

    private final ToolCallPart call = new ToolCallPart("call-1", "get_weather", "{}");

    @Test
    void messageCarriesThePrefix() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        assertEquals("Error: connection refused", handler.handle(call, new RuntimeException("connection refused")));
    }

    @Test
    void messageAlreadyStartingWithThePrefixComesBackVerbatim() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        assertEquals("Error: already marked", handler.handle(call, new RuntimeException("Error: already marked")));
    }

    @Test
    void anEmptyMessageFallsBackToTheExceptionItself() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        String answer = handler.handle(call, new NullPointerException());

        assertEquals("Error: java.lang.NullPointerException", answer);
    }

    @Test
    void anEmptyPrefixAnswersTheMessageUntouched() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("");

        assertEquals("bare", handler.handle(call, new RuntimeException("bare")));
    }

    @Test
    void aNullPrefixIsRefused() {
        assertThrows(NullPointerException.class, () -> ErrorHandlers.message(null));
    }

    @Test
    void fixedAlwaysAnswersTheSameText() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.fixed("the tool is unavailable, tell the user");

        assertEquals("the tool is unavailable, tell the user",
                handler.handle(call, new RuntimeException("connection refused")));
    }

    @Test
    void aNullFixedTextIsRefused() {
        assertThrows(NullPointerException.class, () -> ErrorHandlers.fixed(null));
    }

    @Test
    void rethrowLetsTheOriginalFailureOut() {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.rethrow();
        RuntimeException failure = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> handler.handle(call, failure));

        assertSame(failure, thrown);
    }

}
