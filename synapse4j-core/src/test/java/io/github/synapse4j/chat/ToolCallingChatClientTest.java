package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.json.JsonSchemaBuilder;
import io.github.synapse4j.tool.DefaultToolExecutor;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;

class ToolCallingChatClientTest {

    private final ScriptedChatClient inner = new ScriptedChatClient();

    @Test
    void roundExecutesCallsThenResults() throws Exception {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        AtomicBoolean ran = new AtomicBoolean();
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addUserMessage("hi?");
        request.addTool(tool("alpha", arguments -> {
            ran.set(true);
            return "A";
        }));

        ChatResponse response = client.chat(request);

        assertEquals("done", text(response));
        assertTrue(ran.get());
        assertEquals(2, inner.trips.get());
        // The sent round and the answer the loop folded in are in the history; the batch's results
        // wait in the pending messages until the caller folds in the final answer.
        assertEquals(2, request.getHistoryMessages().size());
        ChatMessage results = request.getPendingMessages().get(0);
        assertEquals(ChatRole.TOOL, results.getRole());
        ToolResultPart result = (ToolResultPart) results.getParts().get(0);
        assertEquals("c1", result.getCallId());
        assertFalse(result.isError());
        assertEquals("A", text(result));
    }

    @Test
    void everyRoundSeesSameRequest() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addUserMessage("hi?");
        request.addTool(tool("alpha", arguments -> "A"));

        client.chat(request);

        assertEquals(2, inner.seen.size());
        assertSame(request, inner.seen.get(0));
        assertSame(request, inner.seen.get(1));
    }

    @Test
    void turnCountsEachTripAround() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        ChatResponse response = client.chat(request);

        assertEquals(List.of(1, 2), inner.turns);
        assertEquals(2, response.getContext().getTurn());
    }

    @Test
    void decoratorCreatesReachableContext() {
        inner.script.add(textResponse("plain"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();

        ChatResponse response = client.chat(request);

        // Created for the round, attached so every trip can find it — unlike a bare client's.
        assertNotNull(request.getContext());
        assertSame(request.getContext(), response.getContext());
        assertSame(request, response.getContext().getRequest());
        assertEquals(1, response.getContext().getTurn());
    }

    @Test
    void declineLeavesCallsUnanswered() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> null);
        ChatRequest request = new ChatRequest();
        request.addUserMessage("hi?");

        ChatResponse response = client.chat(request);

        assertEquals(1, inner.trips.get());
        // Nothing was folded in — the declined answer never reaches continueWith — so the sent
        // message is still what a retry would send.
        assertEquals(0, request.getHistoryMessages().size());
        assertEquals(1, request.getPendingMessages().size());
        assertTrue(toolCalls(response) == 1);
    }

    @Test
    void executorFailureComesStraightOut() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            throw new IllegalStateException("boom");
        });
        ChatRequest request = new ChatRequest();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> client.chat(request));

        assertEquals("boom", thrown.getMessage());
        assertEquals(1, inner.trips.get());
    }

    @Test
    void checkedFailureBecomesSynapseException() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            throw new java.io.IOException("wire broke");
        });
        ChatRequest request = new ChatRequest();

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));

        assertInstanceOf(java.io.IOException.class, thrown.getCause());
        assertEquals(1, inner.trips.get());
    }

    @Test
    void turnCapEndsRound() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(toolCallResponse("c2", "alpha"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, new DefaultToolExecutor(null, null, 2));
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        ChatResponse response = client.chat(request);

        assertEquals(2, inner.trips.get());
        assertEquals(List.of(1, 2), inner.turns);
        // The second answer asked for calls the cap refused to run.
        assertEquals(1, toolCalls(response));
    }

    @Test
    void decoratorDefaultsReachExecutor() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        AtomicBoolean ran = new AtomicBoolean();
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        client.addDefaultTool(tool("alpha", arguments -> {
            ran.set(true);
            return "A";
        }));
        ChatRequest request = new ChatRequest();

        ChatResponse response = client.chat(request);

        assertEquals("done", text(response));
        assertTrue(ran.get());
    }

    @Test
    void innerPerRoundDecoratorOnce() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        AtomicInteger innerAsks = new AtomicInteger();
        AtomicInteger decoratorAsks = new AtomicInteger();
        inner.addToolProvider((it, request) -> {
            innerAsks.incrementAndGet();
            return List.of();
        });
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        client.addToolProvider((it, request) -> {
            decoratorAsks.incrementAndGet();
            return List.of();
        });
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        client.chat(request);

        // Registration decides the frequency: the inner client prepares every round, the
        // decorator once for the whole call.
        assertEquals(2, innerAsks.get());
        assertEquals(1, decoratorAsks.get());
    }

    @Test
    void streamSplicesRoundsIntoSequence() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        inner.streamScript.add(new StreamRound(textResponse("done"), "r2"));
        AtomicBoolean ran = new AtomicBoolean();
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addUserMessage("hi?");
        request.addTool(tool("alpha", arguments -> {
            ran.set(true);
            return "A";
        }));

        ChatStream stream = client.stream(request);
        List<String> seen = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            seen.add(event.getEventType());
        }

        assertEquals(List.of("r1", "r2"), seen);
        assertEquals(2, inner.streamTrips.get());
        assertEquals(0, inner.trips.get());
        assertEquals(List.of(1, 2), inner.streamTurns);
        assertTrue(ran.get());
        assertEquals("done", text(stream.aggregatedResponse()));
        // The sent round and the answer the loop folded in are in the history; the final batch's
        // results wait in the pending messages until the caller folds in the final answer.
        assertEquals(2, request.getHistoryMessages().size());
        ChatMessage results = request.getPendingMessages().get(0);
        assertEquals(ChatRole.TOOL, results.getRole());
        assertEquals("A", text((ToolResultPart) results.getParts().get(0)));
    }

    @Test
    void streamShowsRoundRebindsContext() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        inner.streamScript.add(new StreamRound(textResponse("done"), "r2"));
        List<ChatResponse> atBatch = new ArrayList<>();
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            atBatch.add(context.getResponse());
            return resultsFor(calls);
        });
        ChatRequest request = new ChatRequest();

        ChatStream stream = client.stream(request);
        ChatResponse first = stream.aggregatedResponse();
        assertSame(first, request.getContext().getResponse());

        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        ChatResponse last = stream.aggregatedResponse();
        assertNotSame(first, last);
        assertEquals("done", text(last));
        // The batch ran with round one's answer still on the context; the drain ends on round two's.
        assertSame(first, atBatch.get(0));
        assertEquals(1, toolCalls(first));
        assertSame(last, request.getContext().getResponse());
    }

    @Test
    void streamDeclineLeavesCallsUnanswered() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> null);
        ChatRequest request = new ChatRequest();
        request.addUserMessage("hi?");

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        assertEquals(1, inner.streamTrips.get());
        // Nothing was folded in — the declined answer never reaches continueWith — so the sent
        // message is still what a retry would send.
        assertEquals(0, request.getHistoryMessages().size());
        assertEquals(1, request.getPendingMessages().size());
        assertEquals(1, toolCalls(stream.aggregatedResponse()));
    }

    @Test
    void closeDuringBatchStopsRounds() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        ChatStream[] held = new ChatStream[1];
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            held[0].close();
            return resultsFor(calls);
        });
        ChatRequest request = new ChatRequest();

        ChatStream stream = client.stream(request);
        held[0] = stream;
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertTrue(events.hasNext());
        events.next();
        assertThrows(IllegalStateException.class, events::hasNext);

        assertEquals(1, inner.streamTrips.get());
        // Aborted before the append: neither list gained anything — the request carries only
        // what it went out with, which is nothing.
        assertEquals(0, request.getHistoryMessages().size());
        assertEquals(0, request.getPendingMessages().size());
    }

    @Test
    void batchFailureEndsStreamOnce() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        AtomicInteger attempts = new AtomicInteger();
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        ChatRequest request = new ChatRequest();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertTrue(events.hasNext());
        events.next();
        IllegalStateException first = assertThrows(IllegalStateException.class, events::hasNext);
        IllegalStateException second = assertThrows(IllegalStateException.class, events::hasNext);

        assertSame(first, second);
        assertEquals(1, attempts.get());
        assertEquals(1, inner.streamTrips.get());
    }

    @Test
    void batchErrorReplayedOnce() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        AtomicInteger attempts = new AtomicInteger();
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            attempts.incrementAndGet();
            throw new AssertionError("fatal");
        });
        ChatRequest request = new ChatRequest();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertTrue(events.hasNext());
        events.next();
        AssertionError first = assertThrows(AssertionError.class, events::hasNext);
        AssertionError second = assertThrows(AssertionError.class, events::hasNext);

        // An Error ends the stream just as a RuntimeException does — the batch never re-runs.
        assertSame(first, second);
        assertEquals(1, attempts.get());
    }

    @Test
    void eventPullFailureReleasesStream() {
        inner.streamScript.add(new StreamRound(textResponse("done"), "r1"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        client.addChatCustomizer(new ChatCustomizer() {
            @Override
            public void customizeStreamEvent(ChatClient it, ChatStreamEvent event) {
                throw new IllegalStateException("boom");
            }
        });
        ChatRequest request = new ChatRequest();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        IllegalStateException first = assertThrows(IllegalStateException.class, events::next);
        IllegalStateException second = assertThrows(IllegalStateException.class, events::next);

        assertSame(first, second);
        assertEquals(1, inner.streamTrips.get());
        // The round in progress was released by the failure, not left open behind it.
        assertEquals(1, inner.streamCloses.get());
    }

    @Test
    void drainedStreamReleasesEveryRound() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        inner.streamScript.add(new StreamRound(textResponse("done"), "r2"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // Each round released itself as it ended; the close after that has nothing left to do.
        assertEquals(2, inner.streamCloses.get());
        stream.close();
        assertEquals(2, inner.streamCloses.get());
    }

    @Test
    void responseHookRunsEachRound() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        inner.streamScript.add(new StreamRound(textResponse("done"), "r2"));
        AtomicInteger passes = new AtomicInteger();
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        client.addChatCustomizer(new ChatCustomizer() {
            @Override
            public void customizeResponse(ChatClient runner, ChatResponse response) {
                passes.incrementAndGet();
            }
        });
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // Handed to the inner client whole: each round's drained answer takes the pass.
        assertEquals(2, passes.get());
        assertEquals("done", text(stream.aggregatedResponse()));
        assertSame(stream.aggregatedResponse(), request.getContext().getResponse());
    }

    /** The results an executor would answer the given calls with — one plain answer each. */
    private static List<ToolResultPart> resultsFor(List<ToolCallPart> calls) {
        List<ToolResultPart> results = new ArrayList<>();
        for (ToolCallPart call : calls) {
            results.add(new ToolResultPart(call.getCallId(), call.getName()).addText("A"));
        }
        return results;
    }

    private static int toolCalls(ChatResponse response) {
        int calls = 0;
        for (ContentPart part : response.getMessage().getParts()) {
            if (part instanceof ToolCallPart) {
                calls++;
            }
        }
        return calls;
    }

    private static ChatResponse toolCallResponse(String callId, String tool) {
        ChatResponse response = new ChatResponse();
        response.setMessage(new ChatMessage(ChatRole.ASSISTANT).addPart(new ToolCallPart(callId, tool, "{}")));
        return response;
    }

    private static ChatResponse textResponse(String text) {
        ChatResponse response = new ChatResponse();
        response.setMessage(ChatMessage.assistant(text));
        return response;
    }

    private static String text(ChatResponse response) {
        return ((TextPart) response.getMessage().getParts().get(0)).getText();
    }

    private static String text(ToolResultPart result) {
        return ((TextPart) result.getParts().get(0)).getText();
    }

    /** A declare-only tool whose execution runs the given body. */
    private static Tool tool(String name, Body body) {
        ToolDefinition definition = new ToolDefinition(name, "a test tool", new JsonSchemaBuilder().build());
        return new Tool() {

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public List<ContentPart> execute(String arguments, ChatContext context) throws Exception {
                String text = body.apply(arguments);
                return text == null ? null : List.of(new TextPart(text));
            }
        };
    }

    @FunctionalInterface
    private interface Body {

        String apply(String arguments) throws Exception;
    }

    @Test
    void decoratorCustomizersRunEachRound() {
        inner.streamScript.add(new StreamRound(toolCallResponse("c1", "alpha"), "r1"));
        inner.streamScript.add(new StreamRound(textResponse("done"), "r2"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        List<ChatClient> runners = new ArrayList<>();
        client.addChatCustomizer(new ChatCustomizer() {
            @Override
            public void customizeStreamEvent(ChatClient it, ChatStreamEvent event) {
                runners.add(it);
                event.setEventType("fixed");
            }
        });
        ChatRequest request = new ChatRequest();
        request.addTool(tool("alpha", arguments -> "A"));

        ChatStream stream = client.stream(request);
        List<String> seen = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            seen.add(event.getEventType());
        }

        assertEquals(List.of("fixed", "fixed"), seen);
        // The registration was handed over: each round's inner stream is what ran the chain.
        assertSame(inner, runners.get(0));
    }

    /** An inner client answering from a script, recording what each trip looked like. */
    private static class ScriptedChatClient extends AbstractChatClient {

        final Deque<ChatResponse> script = new ArrayDeque<>();
        final Deque<StreamRound> streamScript = new ArrayDeque<>();
        final List<ChatRequest> seen = new ArrayList<>();
        final List<Integer> turns = new ArrayList<>();
        final List<Integer> streamTurns = new ArrayList<>();
        final AtomicInteger trips = new AtomicInteger();
        final AtomicInteger streamTrips = new AtomicInteger();
        final AtomicInteger streamCloses = new AtomicInteger();

        @Override
        protected ChatResponse doChat(ChatRequest request) {
            seen.add(request);
            turns.add(request.getContext() != null ? request.getContext().getTurn() : -1);
            trips.incrementAndGet();
            return script.removeFirst();
        }

        @Override
        protected ChatStream doStream(ChatRequest request) {
            streamTrips.incrementAndGet();
            streamTurns.add(request.getContext() != null ? request.getContext().getTurn() : -1);
            StreamRound round = streamScript.removeFirst();
            List<ChatStreamEvent> events = new ArrayList<>();
            for (String type : round.eventTypes) {
                ChatStreamEvent event = new ChatStreamEvent();
                event.setEventType(type);
                events.add(event);
            }
            ChatResponse answer = round.answer;
            return new DefaultChatStream(events.iterator(), eventPipeline(), (folded, pulled) -> {
                // The scripted answer arrives whole on the first fold; the tests read the
                // aggregate, not a reconstruction of fragments.
                folded.setMessage(answer.getMessage());
                folded.setFinishReason(answer.getFinishReason());
            }, streamCloses::incrementAndGet);
        }
    }

    /** One scripted streaming round: the events it emits, in order, and the answer they fold into. */
    private static final class StreamRound {

        private final List<String> eventTypes;

        private final ChatResponse answer;

        private StreamRound(ChatResponse answer, String... eventTypes) {
            this.eventTypes = List.of(eventTypes);
            this.answer = answer;
        }
    }

}
