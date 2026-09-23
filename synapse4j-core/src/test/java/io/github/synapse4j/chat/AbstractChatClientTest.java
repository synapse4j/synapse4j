package io.github.synapse4j.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }

    @Test
    void anAnswerWithoutASentContextKeepsWhatTheExchangeProduced() {
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

        assertSame(reported, response.getContext());
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
