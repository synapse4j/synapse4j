package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.Tool;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.tool.DefaultToolExecutor;

class ToolCallingChatClientTest {

    private final ScriptedChatClient inner = new ScriptedChatClient();

    @Test
    void aRoundExecutesTheCallsAndSendsTheResultsBack() throws Exception {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        AtomicBoolean ran = new AtomicBoolean();
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addMessage(userMessage("hi?"));
        request.addTool(tool("alpha", arguments -> {
            ran.set(true);
            return "A";
        }));

        ChatResponse response = client.chat(request);

        assertEquals("done", text(response));
        assertTrue(ran.get());
        assertEquals(2, inner.trips.get());
        assertEquals(3, request.getMessages().size());
        ChatMessage results = request.getMessages().get(2);
        assertEquals(ChatRole.TOOL, results.getRole());
        ToolResultPart result = (ToolResultPart) results.getParts().get(0);
        assertEquals("c1", result.getCallId());
        assertFalse(result.isError());
        assertEquals("A", text(result));
    }

    @Test
    void everyRoundSeesTheSameRequestGrowingInTheCallersHand() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        inner.script.add(textResponse("done"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner);
        ChatRequest request = new ChatRequest();
        request.addMessage(userMessage("hi?"));
        request.addTool(tool("alpha", arguments -> "A"));

        client.chat(request);

        assertEquals(2, inner.seen.size());
        assertSame(request, inner.seen.get(0));
        assertSame(request, inner.seen.get(1));
    }

    @Test
    void theTurnCountsEachTripAround() {
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
    void theDecoratorCreatesAContextTheRequestCanReach() {
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
    void aDeclineEndsTheRoundWithTheCallsUnanswered() {
        inner.script.add(toolCallResponse("c1", "alpha"));
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> null);
        ChatRequest request = new ChatRequest();
        request.addMessage(userMessage("hi?"));

        ChatResponse response = client.chat(request);

        assertEquals(1, inner.trips.get());
        assertEquals(1, request.getMessages().size());
        assertTrue(toolCalls(response) == 1);
    }

    @Test
    void aFailureTheExecutorThrowsComesStraightOut() {
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
    void aCheckedFailureFromTheExecutorArrivesUnderASynapseException() {
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
    void theTurnCapEndsTheRoundWhenItIsReached() {
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
    void defaultsRegisteredOnTheDecoratorReachTheExecutor() {
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
    void theStreamPassesThroughWithoutTheLoop() {
        ToolCallingChatClient client = new ToolCallingChatClient(inner, (calls, available, context) -> {
            throw new IllegalStateException("the stream must not execute anything");
        });

        ChatStream stream = client.stream(new ChatRequest());

        assertNotNull(stream);
        assertEquals(1, inner.streamTrips.get());
        assertEquals(0, inner.trips.get());
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
        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT);
        message.addPart(new ToolCallPart(callId, tool, "{}"));
        response.setMessage(message);
        return response;
    }

    private static ChatResponse textResponse(String text) {
        ChatResponse response = new ChatResponse();
        ChatMessage message = new ChatMessage(ChatRole.ASSISTANT);
        message.addPart(new TextPart(text));
        response.setMessage(message);
        return response;
    }

    private static ChatMessage userMessage(String text) {
        ChatMessage message = new ChatMessage(ChatRole.USER);
        message.addPart(new TextPart(text));
        return message;
    }

    private static String text(ChatResponse response) {
        return ((TextPart) response.getMessage().getParts().get(0)).getText();
    }

    private static String text(ToolResultPart result) {
        return ((TextPart) result.getParts().get(0)).getText();
    }

    /** A declare-only tool whose execution runs the given body. */
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

    /** An inner client answering from a script, recording what each trip looked like. */
    private static class ScriptedChatClient extends AbstractChatClient {

        final Deque<ChatResponse> script = new ArrayDeque<>();
        final List<ChatRequest> seen = new ArrayList<>();
        final List<Integer> turns = new ArrayList<>();
        final AtomicInteger trips = new AtomicInteger();
        final AtomicInteger streamTrips = new AtomicInteger();

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
            return new DefaultChatStream(Collections.emptyIterator(), (response, event) -> {
            }, () -> {
            });
        }
    }

}
