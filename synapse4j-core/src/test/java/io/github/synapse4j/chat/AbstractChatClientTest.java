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
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.Tool;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.tool.FunctionTool;

class AbstractChatClientTest {

    @Test
    void customizersRunInTheOrderTheyWereAddedBeforeTheSubclassSeesTheRequest() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(request -> {
            ran.add("first");
            request.getOptions().setModel("first");
            return request;
        });
        client.addChatRequestCustomizer(request -> {
            ran.add("second");
            request.getOptions().setModel(request.getOptions().getModel() + "+second");
            return request;
        });

        client.chat(new ChatRequest());

        assertEquals(List.of("first", "second"), ran);
        assertEquals("first+second", client.seen.getOptions().getModel());
    }

    @Test
    void theStreamedCallIsPreparedTheSameWay() {
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(request -> {
            request.getOptions().setModel("customized");
            return request;
        });

        client.stream(new ChatRequest());

        assertEquals("customized", client.seen.getOptions().getModel());
    }

    @Test
    void aCustomizerMayAnswerAnotherRequestThanTheOneItWasGiven() {
        ChatRequest replacement = new ChatRequest();
        replacement.getOptions().setModel("replacement");
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(request -> replacement);

        client.chat(new ChatRequest());

        assertSame(replacement, client.seen);
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
        ChatRequestCustomizer customizer = request -> {
            ran.add("run");
            return request;
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
        ChatRequestCustomizer customizer = request -> {
            ran.add("run");
            return request;
        };
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(customizer);

        assertTrue(client.removeChatRequestCustomizer(customizer));
        assertFalse(client.removeChatRequestCustomizer(customizer));
        client.chat(new ChatRequest());

        assertTrue(ran.isEmpty());
    }

    @Test
    void aCustomizerAnsweringNullFailsLoudly() {
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(request -> null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> client.chat(new ChatRequest()));

        assertTrue(thrown.getMessage().contains("customizer"));
    }

    @Test
    void aNullCustomizerIsRefused() {
        StubChatClient client = new StubChatClient();

        assertThrows(NullPointerException.class, () -> client.addChatRequestCustomizer(null));
        assertThrows(NullPointerException.class, () -> client.removeChatRequestCustomizer(null));
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
        ChatRequest replacement = new ChatRequest();
        client.addChatRequestCustomizer(request -> replacement);

        ChatResponse response = client.chat(new ChatRequest());

        assertSame(replacement, response.getContext().getRequest());
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
        client.addChatResponseCustomizer(response -> {
            carried.add(response.getContext());
            return response;
        });

        client.chat(request);

        assertEquals(1, carried.size());
        assertSame(context, carried.get(0));
    }

    @Test
    void aResponseCustomizerMayAnswerAnotherResponse() {
        StubChatClient client = new StubChatClient();
        ChatResponse replacement = new ChatResponse();
        replacement.setId("replacement");
        client.addChatResponseCustomizer(response -> replacement);

        ChatResponse response = client.chat(new ChatRequest());

        assertEquals("replacement", response.getId());
    }

    @Test
    void aResponseCustomizerAnsweringNullFailsLoudly() {
        StubChatClient client = new StubChatClient();
        client.addChatResponseCustomizer(response -> null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> client.chat(new ChatRequest()));

        assertTrue(thrown.getMessage().contains("customizer"));
    }

    @Test
    void aRemovedResponseCustomizerNoLongerRuns() {
        List<String> ran = new ArrayList<>();
        ChatResponseCustomizer customizer = response -> {
            ran.add("run");
            return response;
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
        client.addChatResponseCustomizer(response -> {
            seen.add(response);
            return response;
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
        client.addChatResponseCustomizer(response -> {
            ran.add("run");
            return response;
        });

        ChatStream stream = client.stream(new ChatRequest());
        stream.close();

        assertTrue(ran.isEmpty());
    }

    @Test
    void requestCustomizersRunByTheirRegisteredOrderWithInsertionOrderAsTheTieBreak() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatRequestCustomizer(namedRequest("late", ran), 10);
        client.addChatRequestCustomizer(namedRequest("early", ran), -5);
        client.addChatRequestCustomizer(namedRequest("first-default", ran), ChatClient.DEFAULT_ORDER);
        client.addChatRequestCustomizer(request -> {
            ran.add("lambda");
            return request;
        });
        client.addChatRequestCustomizer(namedRequest("second-default", ran), ChatClient.DEFAULT_ORDER);

        client.chat(new ChatRequest());

        assertEquals(List.of("early", "first-default", "lambda", "second-default", "late"), ran);
    }

    @Test
    void responseCustomizersRunByTheirRegisteredOrderWithInsertionOrderAsTheTieBreak() {
        List<String> ran = new ArrayList<>();
        StubChatClient client = new StubChatClient();
        client.addChatResponseCustomizer(namedResponse("late", ran), 10);
        client.addChatResponseCustomizer(namedResponse("early", ran), -5);
        client.addChatResponseCustomizer(response -> {
            ran.add("lambda");
            return response;
        });
        client.addChatResponseCustomizer(namedResponse("after-lambda", ran), ChatClient.DEFAULT_ORDER);

        client.chat(new ChatRequest());

        assertEquals(List.of("early", "lambda", "after-lambda", "late"), ran);
    }

    @Test
    void theClientDefaultsApplyBetweenTheCustomizersThatStraddleThem() {
        List<String> ran = new ArrayList<>();
        DefaultsChatClient client = new DefaultsChatClient();
        client.addChatRequestCustomizer(request -> {
            ran.add("before:" + request.getOptions().getModel());
            return request;
        }, ChatClient.DEFAULT_ORDER - 1);
        client.addChatRequestCustomizer(request -> {
            ran.add("after:" + request.getOptions().getModel());
            return request;
        });

        client.chat(new ChatRequest());

        assertEquals(List.of("before:null", "after:inherited"), ran);
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
    void aNullToolOrNameIsRefused() {
        StubChatClient client = new StubChatClient();

        assertThrows(NullPointerException.class, () -> client.addDefaultTool(null));
        assertThrows(NullPointerException.class, () -> client.removeDefaultTool(null));
        assertThrows(NullPointerException.class,
                () -> client.addDefaultTool(FunctionTool.of(new ToolDefinition(null, "does things", "{}"))));
    }

    /** A declare-only tool carrying the given name — all the merge looks at. */
    private static Tool tool(String name) {
        return FunctionTool.of(new ToolDefinition(name, "does things", "{}"));
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
        return request -> {
            ran.add(name);
            return request;
        };
    }

    /** A response customizer that records a name instead of touching the response. */
    private static ChatResponseCustomizer namedResponse(String name, List<String> ran) {
        return response -> {
            ran.add(name);
            return response;
        };
    }

    /** A client whose defaults fill in a model, to show where in the sequence they land. */
    static class DefaultsChatClient extends StubChatClient {

        int defaultsApplied;

        @Override
        protected ChatRequest applyDefaults(ChatRequest request) {
            defaultsApplied++;
            request.getOptions().setModel("inherited");
            return super.applyDefaults(request);
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
            return new DefaultChatStream(Collections.emptyIterator(), (response, event) -> {
            }, () -> {
            });
        }
    }

}
