package io.github.synapse4j.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.Tool;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.ToolNotFoundException;

class DefaultToolExecutorTest {

    private final DefaultToolExecutor executor = new DefaultToolExecutor();

    @Test
    void answersComeBackPairedWithTheirCalls() throws Exception {
        List<ToolResultPart> results = executor.execute(
                List.of(call("c1", "alpha"), call("c2", "beta")),
                List.of(tool("alpha", arguments -> "A"), tool("beta", arguments -> "B")),
                null);

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).getCallId());
        assertEquals("alpha", results.get(0).getName());
        assertEquals("A", text(results.get(0)));
        assertFalse(results.get(0).isError());
        assertEquals("c2", results.get(1).getCallId());
        assertEquals("B", text(results.get(1)));
    }

    @Test
    void aFailureComesBackMarkedUnderTheDefaultPrefix() throws Exception {
        List<ToolResultPart> results = executor.execute(
                List.of(call("c1", "flaky")),
                List.of(tool("flaky", arguments -> {
                    throw new IllegalStateException("connection refused");
                })),
                null);

        assertTrue(results.get(0).isError());
        assertEquals("Error: connection refused", text(results.get(0)));
        assertEquals("c1", results.get(0).getCallId());
    }

    @Test
    void aCustomHandlerDecidesWhatTheModelSees() throws Exception {
        DefaultToolExecutor custom = new DefaultToolExecutor(null, ErrorHandlers.fixed("ask the user instead"));

        List<ToolResultPart> results = custom.execute(
                List.of(call("c1", "flaky")),
                List.of(tool("flaky", arguments -> {
                    throw new IllegalStateException("connection refused");
                })),
                null);

        assertTrue(results.get(0).isError());
        assertEquals("ask the user instead", text(results.get(0)));
    }

    @Test
    void anAbortKeepsLaterCallsFromStarting() {
        AtomicBoolean laterRan = new AtomicBoolean();
        DefaultToolExecutor strict = new DefaultToolExecutor(null, ErrorHandlers.rethrow());

        assertThrows(IllegalStateException.class, () -> strict.execute(
                List.of(call("c1", "boom"), call("c2", "later")),
                List.of(tool("boom", arguments -> {
                    throw new IllegalStateException("boom");
                }), tool("later", arguments -> {
                    laterRan.set(true);
                    return "L";
                })),
                null));

        assertFalse(laterRan.get());
    }

    @Test
    void anUnknownNameFailsThroughTheSamePath() throws Exception {
        List<ToolResultPart> results = executor.execute(
                List.of(call("c1", "ghost")),
                List.of(tool("alpha", arguments -> "A")),
                null);

        assertTrue(results.get(0).isError());
        assertEquals("Error: no tool named \"ghost\"", text(results.get(0)));
    }

    @Test
    void aCustomHandlerCanTellAnUnknownNameFromARealFailure() throws Exception {
        AtomicReference<Exception> seen = new AtomicReference<>();
        DefaultToolExecutor observed = new DefaultToolExecutor(null, (call, failure) -> {
            seen.set(failure);
            return "noted";
        });

        observed.execute(
                List.of(call("c1", "ghost")),
                List.of(tool("alpha", arguments -> "A")),
                null);

        assertInstanceOf(ToolNotFoundException.class, seen.get());
    }

    @Test
    void anAnswerOfNullCountsAsAFailure() throws Exception {
        List<ToolResultPart> results = executor.execute(
                List.of(call("c1", "quiet")),
                List.of(tool("quiet", arguments -> null)),
                null);

        assertTrue(results.get(0).isError());
        assertEquals("Error: tool answered null", text(results.get(0)));
    }

    @Test
    void anEmptyBatchAnswersNothing() throws Exception {
        assertTrue(executor.execute(List.of(), List.of(), null).isEmpty());
    }

    @Test
    void aHandlerAnsweringNullAborts() {
        DefaultToolExecutor broken = new DefaultToolExecutor(null, (call, failure) -> null);

        assertThrows(NullPointerException.class, () -> broken.execute(
                List.of(call("c1", "boom")),
                List.of(tool("boom", arguments -> {
                    throw new IllegalStateException("boom");
                })),
                null));
    }

    @Test
    void workersRunTheBatchConcurrentlyButAnswersStillComeBackInCallOrder() throws Exception {
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            DefaultToolExecutor parallel = new DefaultToolExecutor(workers, null);

            List<ToolResultPart> results = parallel.execute(
                    List.of(call("c1", "alpha"), call("c2", "beta")),
                    List.of(tool("alpha", arguments -> {
                        if (!secondDone.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the batch ran inline");
                        }
                        return "A";
                    }), tool("beta", arguments -> {
                        secondDone.countDown();
                        return "B";
                    })),
                    null);

            assertEquals("A", text(results.get(0)));
            assertFalse(results.get(0).isError());
            assertEquals("B", text(results.get(1)));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void anAbortWaitsForTheBatchAndRethrowsTheFirstFailureInCallOrder() throws Exception {
        CountDownLatch secondFailed = new CountDownLatch(1);
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        AtomicBoolean firstRan = new AtomicBoolean();
        AtomicBoolean secondRan = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            DefaultToolExecutor strict = new DefaultToolExecutor(workers, ErrorHandlers.rethrow());

            Exception thrown = assertThrows(IllegalStateException.class, () -> strict.execute(
                    List.of(call("c1", "t1"), call("c2", "t2")),
                    List.of(tool("t1", arguments -> {
                        firstRan.set(true);
                        if (!secondFailed.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the second call never ran");
                        }
                        throw first;
                    }), tool("t2", arguments -> {
                        secondRan.set(true);
                        secondFailed.countDown();
                        throw second;
                    })),
                    null));

            assertSame(first, thrown);
            assertTrue(firstRan.get());
            assertTrue(secondRan.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void anInterruptedWaitRestoresTheStatusAndComesStraightOut() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AtomicReference<Exception> caught = new AtomicReference<>();
        AtomicBoolean interruptSurvived = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                executor(workers).execute(
                        List.of(call("c1", "blocking")),
                        List.of(tool("blocking", arguments -> {
                            started.countDown();
                            release.await();
                            return "done";
                        })),
                        null);
            } catch (Exception e) {
                caught.set(e);
                interruptSurvived.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            caller.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(5000);

            assertInstanceOf(InterruptedException.class, caught.get());
            assertTrue(interruptSurvived.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static DefaultToolExecutor executor(ExecutorService workers) {
        return new DefaultToolExecutor(workers, null);
    }

    private static ToolCallPart call(String id, String name) {
        return new ToolCallPart(id, name, "{}");
    }

    private static String text(ToolResultPart result) {
        return ((TextPart) result.getParts().get(0)).getText();
    }

    /** A tool whose execution runs the given body against the raw arguments. */
    private static Tool tool(String name, Body body) {
        ToolDefinition definition = new ToolDefinition(name, "a test tool", "{}");
        return new Tool() {

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public String execute(String arguments, ChatContext context) throws Exception {
                return body.apply(arguments);
            }
        };
    }

    @FunctionalInterface
    private interface Body {

        String apply(String arguments) throws Exception;
    }

}
