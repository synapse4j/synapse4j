package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.tool.ManualTool;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolDefinition;
import io.github.synapse4j.tool.ToolProvider;

class AbstractChatClientTest {

    @Test
    void customizersRunInTheOrderTheyWereAddedBeforeTheSubclassSeesTheRequest() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer((it, request) -> {
            ran.add("first");
            request.getOptions().setModel("first");
        });
        client.addChatRequestCustomizer((it, request) -> {
            ran.add("second");
            request.getOptions().setModel(request.getOptions().getModel() + "+second");
        });

        client.chat(new ChatRequest());

        assertEquals(List.of("first", "second"), ran);
        assertEquals("first+second", client.seen.getOptions().getModel());
    }

    @Test
    void theStreamedCallIsPreparedTheSameWay() {
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer((it, request) -> {
            request.getOptions().setModel("customized");
        });

        client.stream(new ChatRequest());

        assertEquals("customized", client.seen.getOptions().getModel());
    }

    @Test
    void aRequestNoCustomizerTouchesIsHandedOnUnchanged() {
        StubChatClient client = new StubChatClient();
        ChatRequest request = new ChatRequest();

        client.chat(request);

        assertSame(request, client.seen);
    }

    @Test
    void theSameCustomizerAddedTwiceRunsTwice() {
        List<String> ran = new ArrayList<>();
        ChatRequestCustomizer customizer = (it, request) -> {
            ran.add("run");
        };
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(customizer);
        client.addChatRequestCustomizer(customizer);

        client.chat(new ChatRequest());

        assertEquals(List.of("run", "run"), ran);
    }

    @Test
    void aRemovedCustomizerNoLongerRuns() {
        List<String> ran = new ArrayList<>();
        ChatRequestCustomizer customizer = (it, request) -> {
            ran.add("run");
        };
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(customizer);

        assertTrue(client.removeChatRequestCustomizer(customizer));
        assertFalse(client.removeChatRequestCustomizer(customizer));
        client.chat(new ChatRequest());

        assertTrue(ran.isEmpty());
    }

    @Test
    void oneRemovalTakesEveryRegistrationOfTheCustomizer() {
        List<String> ran = new ArrayList<>();
        ChatRequestCustomizer customizer = (it, request) -> {
            ran.add("run");
        };
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(customizer);
        client.addChatRequestCustomizer(customizer);

        assertTrue(client.removeChatRequestCustomizer(customizer));
        client.chat(new ChatRequest());

        assertTrue(ran.isEmpty());
    }

    @Test
    void aNullCustomizerIsRefused() {
        StubChatClient client = new StubChatClient();

        assertThrows(NullPointerException.class, () -> client.addChatRequestCustomizer(null));
        assertThrows(NullPointerException.class, () -> client.removeChatRequestCustomizer(null));
    }

    @Test
    void customizersReceiveTheClientThatAppliesThem() {
        StubChatClient client = new StubChatClient();
        List<ChatClient> applied = new ArrayList<>();
        client.addChatRequestCustomizer((it, request) -> {
            applied.add(it);
        });
        client.addChatResponseCustomizer((it, response) -> {
            applied.add(it);
        });

        client.chat(new ChatRequest());
        ChatStream stream = client.stream(new ChatRequest());
        var events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // Four passes: the request and the response of each call — the streamed response once
        // the stream is drained — and every one of them names this client.
        assertEquals(4, applied.size());
        applied.forEach(chatClient -> assertSame(client, chatClient));
    }

    @Test
    void theContextRidesBackOnTheAnswer() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        context.setSessionId("s-1");
        ChatRequest request = new ChatRequest();
        request.setContext(context);

        ChatResponse response = client.chat(request);

        assertSame(context, response.getContext());
    }

    @Test
    void continuingFoldsTheAnswerIntoTheHistoryAndTakesTheContextItCameBackOn() {
        ChatContext context = new ChatContext();
        context.setSessionId("session-1");
        ChatResponse answer = new ChatResponse();
        answer.setMessage(ChatMessage.assistant("hello"));
        answer.setContext(context);
        StubChatClient client = new StubChatClient();
        ChatRequest request = new ChatRequest();
        request.addPendingMessage(ChatMessage.user("hi"));

        client.continueWith(request, answer);

        // The answer's turn joins the history; what was sent stays pending until the exchange
        // that carried it succeeds and the client archives it.
        assertEquals(1, request.getHistoryMessages().size());
        assertSame(answer.getMessage(), request.getHistoryMessages().get(0));
        assertEquals(1, request.getPendingMessages().size());
        // The exchange's own context is the one the conversation goes on with, since that is where
        // an adopted session id and the turn live.
        assertSame(context, request.getContext());
    }

    @Test
    void whatTheCallSentJoinsTheHistoryWhenTheExchangeSucceeds() {
        StubChatClient client = new StubChatClient();
        ChatRequest request = new ChatRequest();
        ChatMessage sent = ChatMessage.user("hi");
        request.addPendingMessage(sent);

        client.chat(request);

        assertTrue(request.getPendingMessages().isEmpty());
        assertEquals(1, request.getHistoryMessages().size());
        assertSame(sent, request.getHistoryMessages().get(0));
    }

    @Test
    void aFailedSendLeavesThePendingMessagesWhereARetryCanSendThem() {
        StubChatClient client = new StubChatClient() {
            @Override
            protected ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("send failed");
            }
        };
        ChatRequest request = new ChatRequest();
        request.addPendingMessage(ChatMessage.user("hi"));

        assertThrows(IllegalStateException.class, () -> client.chat(request));

        assertEquals(1, request.getPendingMessages().size());
        assertTrue(request.getHistoryMessages().isEmpty());
    }

    @Test
    void theStreamedAnswerCarriesTheContextToo() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest request = new ChatRequest();
        request.setContext(context);

        ChatStream stream = client.stream(request);

        assertSame(context, stream.aggregatedResponse().getContext());
        assertSame(stream.aggregatedResponse(), context.getResponse());
    }

    @Test
    void anAnswerWhoseExchangeReportedASessionIdHasItAdopted() {
        ChatContext reported = new ChatContext();
        reported.setSessionId("provider-1");
        StubChatClient client = new StubChatClient() {
            @Override
            protected ChatResponse doChat(ChatRequest request) {
                ChatResponse response = super.doChat(request);
                // What a provider module does when the protocol reports a session id of its own.
                response.setContext(reported);
                return response;
            }
        };

        ChatResponse response = client.chat(new ChatRequest());

        ChatContext context = response.getContext();
        assertNotSame(reported, context);
        assertEquals("provider-1", context.getSessionId());
        assertSame(client.seen, context.getRequest());
        assertSame(response, context.getResponse());
    }

    @Test
    void aRequestWithoutAContextGetsOneItNeverSees() {
        StubChatClient client = new StubChatClient();
        ChatRequest request = new ChatRequest();

        ChatResponse response = client.chat(request);

        // The fresh context is call-scoped: it goes back on the answer, never onto the request.
        assertNull(request.getContext());
        ChatContext context = response.getContext();
        assertNotNull(context);
        assertEquals(0, context.getTurn());
        assertSame(client.seen, context.getRequest());
        assertSame(response, context.getResponse());
    }

    @Test
    void theContextCarriesTheRequestAsItWentOut() {
        StubChatClient client = new StubChatClient();
        ChatRequest request = new ChatRequest();

        ChatResponse response = client.chat(request);

        assertSame(request, response.getContext().getRequest());
    }

    @Test
    void everyCallOverwritesWhatTheContextCarried() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest first = new ChatRequest();
        first.setContext(context);
        client.chat(first);
        ChatRequest second = new ChatRequest();
        second.setContext(context);

        ChatResponse response = client.chat(second);

        assertSame(second, context.getRequest());
        assertSame(response, context.getResponse());
    }

    @Test
    void theClientNeverTouchesTheTurn() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        context.setTurn(7);
        ChatRequest request = new ChatRequest();
        request.setContext(context);

        client.chat(request);

        assertEquals(7, context.getTurn());
    }

    @Test
    void responseCustomizersRunAfterTheContextHasRiddenBack() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest request = new ChatRequest();
        request.setContext(context);
        List<ChatContext> carried = new ArrayList<>();
        client.addChatResponseCustomizer((it, response) -> {
            carried.add(response.getContext());
        });

        client.chat(request);

        assertEquals(1, carried.size());
        assertSame(context, carried.get(0));
    }

    @Test
    void everyCustomizerIsHandedTheAnswerCarryingTheContext() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest request = new ChatRequest();
        request.setContext(context);
        List<ChatContext> seen = new ArrayList<>();
        List<ChatResponse> handled = new ArrayList<>();
        client.addChatResponseCustomizer((it, response) -> {
            seen.add(response.getContext());
            handled.add(response);
        });
        client.addChatResponseCustomizer((it, response) -> {
            seen.add(response.getContext());
            handled.add(response);
        });

        ChatResponse response = client.chat(request);

        // Every customizer is handed the exchange's own answer, already carrying the context.
        assertEquals(2, seen.size());
        assertSame(context, seen.get(0));
        assertSame(context, seen.get(1));
        assertSame(response, handled.get(0));
        assertSame(response, handled.get(1));
        assertSame(context, response.getContext());
        assertSame(response, context.getResponse());
    }

    @Test
    void theStreamedAnswerIsStampedTheSameWay() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest request = new ChatRequest();
        request.setContext(context);
        List<ChatResponse> handled = new ArrayList<>();
        client.addChatResponseCustomizer((it, response) -> handled.add(response));

        ChatStream stream = client.stream(request);
        var events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        ChatResponse response = stream.aggregatedResponse();
        assertSame(context, response.getContext());
        assertSame(response, context.getResponse());
        assertSame(response, handled.get(0));
    }

    @Test
    void eventCustomizersRunBeforeTheFoldAndTheCallerSeesTheSameEvent() {
        StubChatClient client = new StubChatClient();
        client.addChatStreamEventCustomizer((it, event) -> {
            event.setEventType("fixed");
        });

        ChatStream stream = client.stream(new ChatRequest());
        List<String> seen = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            seen.add(event.getEventType());
        }

        assertEquals(List.of("fixed"), seen);
        assertEquals("fixed", stream.aggregatedResponse().getFinishReason());
    }

    @Test
    void eventCustomizersRunInOrderEachAnsweringTheNext() {
        StubChatClient client = new StubChatClient();
        client.addChatStreamEventCustomizer((it, event) -> {
            event.setEventType(event.getEventType() + "+first");
        });
        client.addChatStreamEventCustomizer((it, event) -> {
            event.setEventType(event.getEventType() + "+second");
        });

        ChatStream stream = client.stream(new ChatRequest());
        var events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        assertEquals("stub+first+second", stream.aggregatedResponse().getFinishReason());
    }

    @Test
    void anEventCustomizerRegisteredAfterTheStreamOpenedDoesNotJoinIt() {
        StubChatClient client = new StubChatClient();
        ChatStream stream = client.stream(new ChatRequest());
        client.addChatStreamEventCustomizer((it, event) -> {
            event.setEventType("late");
        });

        var events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        assertEquals("stub", stream.aggregatedResponse().getFinishReason());
    }

    @Test
    void anEventCustomizerNeverRunsOnABlockingCall() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatStreamEventCustomizer((it, event) -> {
            ran.add("run");
        });

        client.chat(new ChatRequest());

        assertTrue(ran.isEmpty());
    }

    @Test
    void aRemovedResponseCustomizerNoLongerRuns() {
        List<String> ran = new ArrayList<>();
        ChatResponseCustomizer customizer = (it, response) -> {
            ran.add("run");
        };
        StubChatClient client = new StubChatClient();
        client.addChatResponseCustomizer(customizer);

        assertTrue(client.removeChatResponseCustomizer(customizer));
        assertFalse(client.removeChatResponseCustomizer(customizer));
        client.chat(new ChatRequest());

        assertTrue(ran.isEmpty());
    }

    @Test
    void theStreamRunsItsResponseCustomizersOnceItIsDrained() {
        StubChatClient client = new StubChatClient();
        ChatContext context = new ChatContext();
        ChatRequest request = new ChatRequest();
        request.setContext(context);
        List<ChatResponse> seen = new ArrayList<>();
        client.addChatResponseCustomizer((it, response) -> {
            seen.add(response);
        });

        ChatStream stream = client.stream(request);
        var events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        assertEquals(1, seen.size());
        assertSame(context, seen.get(0).getContext());
        assertSame(seen.get(0), stream.aggregatedResponse());
    }

    @Test
    void anAbandonedStreamNeverRunsItsResponseCustomizers() {
        StubChatClient client = new StubChatClient();
        List<String> ran = new ArrayList<>();
        client.addChatResponseCustomizer((it, response) -> {
            ran.add("run");
        });

        ChatStream stream = client.stream(new ChatRequest());
        stream.close();

        assertTrue(ran.isEmpty());
    }

    @Test
    void requestCustomizersRunInTheOrderTheyWereAdded() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(namedRequest("first", ran));
        client.addChatRequestCustomizer(namedRequest("second", ran));
        client.addChatRequestCustomizer(namedRequest("third", ran));

        client.chat(new ChatRequest());

        assertEquals(List.of("first", "second", "third"), ran);
    }

    @Test
    void responseCustomizersRunInTheOrderTheyWereAdded() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatResponseCustomizer(namedResponse("first", ran));
        client.addChatResponseCustomizer(namedResponse("second", ran));
        client.addChatResponseCustomizer(namedResponse("third", ran));

        client.chat(new ChatRequest());

        assertEquals(List.of("first", "second", "third"), ran);
    }

    @Test
    void theClientDefaultsApplyBeforeEveryCustomizer() {
        List<String> ran = new ArrayList<>();
        DefaultsChatClient client = new DefaultsChatClient();
        client.addChatRequestCustomizer((it, request) -> {
            ran.add("first:" + request.getOptions().getModel());
        });
        client.addChatRequestCustomizer((it, request) -> {
            ran.add("second:" + request.getOptions().getModel());
        });

        client.chat(new ChatRequest());

        assertEquals(List.of("first:inherited", "second:inherited"), ran);
        assertEquals(1, client.defaultsApplied);
    }

    @Test
    void theClientDefaultsApplyEvenWhenNoCustomizerIsRegistered() {
        DefaultsChatClient client = new DefaultsChatClient();

        client.chat(new ChatRequest());

        assertEquals(1, client.defaultsApplied);
        assertEquals("inherited", client.seen.getOptions().getModel());
    }

    /** A request customizer that records a name instead of touching the request. */
    @Test
    void defaultToolsGoOutBeforeTheOnesTheRequestItselfCarries() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));
        ChatRequest request = new ChatRequest();
        request.addTool(tool("c"));

        client.chat(request);

        assertEquals(List.of("a", "b", "c"), names(client.seen.getTools()));
    }

    @Test
    void aRequestToolOfADefaultsNameStandsInItsSlot() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));
        client.addDefaultTool(tool("c"));
        ChatRequest request = new ChatRequest();
        Tool replacement = tool("b");
        request.addTool(replacement);
        request.addTool(tool("d"));

        client.chat(request);

        assertEquals(List.of("a", "b", "c", "d"), names(client.seen.getTools()));
        assertSame(replacement, client.seen.getTools().get(1));
    }

    @Test
    void sendingTheSameRequestTwiceProducesTheSameOrder() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));
        ChatRequest request = new ChatRequest();
        request.addTool(tool("c"));

        client.chat(request);
        List<String> first = names(request.getTools());
        client.chat(request);

        assertEquals(List.of("a", "b", "c"), first);
        assertEquals(first, names(request.getTools()));
    }

    @Test
    void registeringANameAgainReplacesTheToolInPlace() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));
        Tool upgraded = tool("a");
        client.addDefaultTool(upgraded);

        client.chat(new ChatRequest());

        assertEquals(List.of("a", "b"), names(client.seen.getTools()));
        assertSame(upgraded, client.seen.getTools().get(0));
    }

    @Test
    void aRemovedDefaultNoLongerGoesOut() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));

        assertTrue(client.removeDefaultTool("a"));
        assertFalse(client.removeDefaultTool("a"));

        client.chat(new ChatRequest());

        assertEquals(List.of("b"), names(client.seen.getTools()));
    }

    @Test
    void aProviderIsAskedOncePerCallDuringPreparation() {
        StubChatClient client = new StubChatClient();
        List<String> asked = new ArrayList<>();
        List<ChatRequest> handed = new ArrayList<>();
        client.addToolProvider((it, request) -> {
            assertSame(client, it);
            asked.add("asked");
            handed.add(request);
            return List.of(tool("p"));
        });
        ChatRequest request = new ChatRequest();

        client.chat(request);

        assertEquals(List.of("asked"), asked);
        assertSame(request, handed.get(0));
        assertEquals(List.of("p"), names(client.seen.getTools()));
    }

    @Test
    void everyCustomizerSeesWhatTheProvidersAnswered() {
        StubChatClient client = new StubChatClient();
        client.addToolProvider((it, request) -> List.of(tool("p")));
        List<String> seen = new ArrayList<>();
        client.addChatRequestCustomizer((it, request) -> {
            seen.addAll(names(request.getTools()));
        });

        client.chat(new ChatRequest());

        assertEquals(List.of("p"), seen);
    }

    @Test
    void aProviderReplacesADefaultOfTheSameNameInItsSlot() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("a"));
        client.addDefaultTool(tool("b"));
        Tool provided = tool("a");
        client.addToolProvider((it, request) -> List.of(provided, tool("c")));

        client.chat(new ChatRequest());

        assertEquals(List.of("a", "b", "c"), names(client.seen.getTools()));
        assertSame(provided, client.seen.getTools().get(0));
    }

    @Test
    void aRequestToolReplacesWhatAProviderAnswered() {
        StubChatClient client = new StubChatClient();
        client.addToolProvider((it, request) -> List.of(tool("a"), tool("b")));
        ChatRequest request = new ChatRequest();
        Tool requestA = tool("a");
        request.addTool(requestA);

        client.chat(request);

        assertEquals(List.of("a", "b"), names(client.seen.getTools()));
        assertSame(requestA, client.seen.getTools().get(0));
    }

    @Test
    void duplicateNamesWithinTheRequestCollapseToTheLast() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("d"));
        ChatRequest request = new ChatRequest();
        Tool first = tool("a");
        Tool second = tool("a");
        request.addTool(first);
        request.addTool(second);

        client.chat(request);

        // The caller was always told to keep names unique; when a merge runs anyway, one
        // name means one tool and the last answer is the one that goes out.
        assertEquals(List.of("d", "a"), names(client.seen.getTools()));
        assertSame(second, client.seen.getTools().get(1));
    }

    @Test
    void providersFillTheirAnswersInRegistrationOrder() {
        StubChatClient client = new StubChatClient();
        client.addDefaultTool(tool("d"));
        Tool fromFirst = tool("x");
        Tool sharedFromSecond = tool("s");
        client.addToolProvider((it, request) -> List.of(fromFirst, tool("s")));
        client.addToolProvider((it, request) -> List.of(sharedFromSecond, tool("y")));

        client.chat(new ChatRequest());

        assertEquals(List.of("d", "x", "s", "y"), names(client.seen.getTools()));
        assertSame(sharedFromSecond, client.seen.getTools().get(2));
    }

    @Test
    void aProviderAnsweringNullFailsLoudly() {
        StubChatClient client = new StubChatClient();
        client.addToolProvider((it, request) -> null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> client.chat(new ChatRequest()));

        assertEquals("tool provider answered null", thrown.getMessage());
    }

    @Test
    void aRemovedProviderIsNoLongerAsked() {
        StubChatClient client = new StubChatClient();
        AtomicInteger asks = new AtomicInteger();
        ToolProvider provider = (it, request) -> {
            asks.incrementAndGet();
            return List.of();
        };
        client.addToolProvider(provider);
        assertTrue(client.removeToolProvider(provider));
        assertFalse(client.removeToolProvider(provider));

        client.chat(new ChatRequest());

        assertEquals(0, asks.get());
    }

    @Test
    void theSameProviderRegisteredTwiceIsAskedOnce() {
        StubChatClient client = new StubChatClient();
        AtomicInteger asks = new AtomicInteger();
        ToolProvider provider = (it, request) -> {
            asks.incrementAndGet();
            return List.of(tool("p"));
        };
        client.addToolProvider(provider);
        client.addToolProvider(provider);

        client.chat(new ChatRequest());

        // Registration records presence: the same source twice is still one source.
        assertEquals(1, asks.get());
        assertEquals(List.of("p"), names(client.seen.getTools()));
    }

    @Test
    void aNullProviderIsRefused() {
        StubChatClient client = new StubChatClient();

        assertThrows(NullPointerException.class, () -> client.addToolProvider(null));
        assertThrows(NullPointerException.class, () -> client.removeToolProvider(null));
    }

    @Test
    void aNullToolOrNameIsRefused() {
        StubChatClient client = new StubChatClient();

        assertThrows(NullPointerException.class, () -> client.addDefaultTool(null));
        assertThrows(NullPointerException.class, () -> client.removeDefaultTool(null));
        assertThrows(NullPointerException.class,
                () -> client.addDefaultTool(new ManualTool(new ToolDefinition(null, "does things", "{}"))));
    }

    /** A declare-only tool carrying the given name — all the merge looks at. */
    private static Tool tool(String name) {
        return new ManualTool(new ToolDefinition(name, "does things", "{}"));
    }

    /** The tools' names, in the order they would go out. */
    private static List<String> names(List<Tool> tools) {
        List<String> names = new ArrayList<>();
        for (Tool tool : tools) {
            names.add(tool.name());
        }
        return names;
    }

    private static ChatRequestCustomizer namedRequest(String name, List<String> ran) {
        return (it, request) -> {
            ran.add(name);
        };
    }

    /** A response customizer that records a name instead of touching the response. */
    private static ChatResponseCustomizer namedResponse(String name, List<String> ran) {
        return (it, response) -> {
            ran.add(name);
        };
    }

    /** A client whose defaults fill in a model, to show where in the sequence they land. */
    static class DefaultsChatClient extends StubChatClient {

        int defaultsApplied;

        @Override
        protected void applyDefaults(ChatRequest request) {
            defaultsApplied++;
            request.getOptions().setModel("inherited");
            super.applyDefaults(request);
        }
    }

    /** A client that records what it was handed instead of doing an exchange. */
    static class StubChatClient extends AbstractChatClient {

        ChatRequest seen;

        @Override
        protected ChatResponse doChat(ChatRequest request) {
            this.seen = request;
            return new ChatResponse();
        }

        @Override
        protected ChatStream doStream(ChatRequest request) {
            this.seen = request;
            ChatStreamEvent event = new ChatStreamEvent();
            event.setEventType("stub");
            return new DefaultChatStream(List.of(event).iterator(), eventPipeline(),
                    (response, pulled) -> response.setFinishReason(pulled.getEventType()), () -> {
                    });
        }
    }

}
