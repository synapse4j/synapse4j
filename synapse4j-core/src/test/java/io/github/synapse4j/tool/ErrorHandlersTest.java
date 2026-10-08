package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ToolCallPart;

class ErrorHandlersTest {

    private final ToolCallPart call = new ToolCallPart("call-1", "get_weather", "{}");

    @Test
    void messageCarriesPrefix() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        assertEquals("Error: connection refused", handler.handle(call, new RuntimeException("connection refused")));
    }

    @Test
    void prefixedMessageReturnedVerbatim() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        assertEquals("Error: already marked", handler.handle(call, new RuntimeException("Error: already marked")));
    }

    @Test
    void silentFailureUsesExceptionText() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("Error: ");

        // Nothing to show either way: no message at all, and one of no length.
        assertEquals("Error: java.lang.RuntimeException", handler.handle(call, new RuntimeException("")));
        assertEquals("Error: java.lang.NullPointerException", handler.handle(call, new NullPointerException()));
    }

    @Test
    void emptyPrefixAnswersMessageUntouched() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.message("");

        assertEquals("bare", handler.handle(call, new RuntimeException("bare")));
    }

    @Test
    void fixedAlwaysSameText() throws Exception {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.fixed("the tool is unavailable, tell the user");

        assertEquals("the tool is unavailable, tell the user",
                handler.handle(call, new RuntimeException("connection refused")));
    }

    @Test
    void rethrowReleasesOriginalFailure() {
        ToolExecutor.ErrorHandler handler = ErrorHandlers.rethrow();
        RuntimeException failure = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> handler.handle(call, failure));

        assertSame(failure, thrown);
    }

}
