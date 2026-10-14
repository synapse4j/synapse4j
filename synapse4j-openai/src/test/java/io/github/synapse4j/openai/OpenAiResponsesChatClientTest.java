package io.github.synapse4j.openai;

import static io.github.synapse4j.openai.OpenAiFixtures.assertNoNullValues;
import static io.github.synapse4j.openai.OpenAiFixtures.requestWithModel;
import static io.github.synapse4j.openai.OpenAiFixtures.textOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatFinishReason;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ListPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.RawPart;
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonSchemaBuilder;
import io.github.synapse4j.tool.ManualTool;
import io.github.synapse4j.tool.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesChatClientTest {

    /** Captures the outgoing request and replays a canned response. */
    static class StubHttpClient implements HttpClient {

        HttpRequest captured;

        /** The body as it went out; asserted instead of a re-write of a request that has moved on. */
        ByteArrayOutputStream sent = new ByteArrayOutputStream();

        DefaultHttpResponse canned = new DefaultHttpResponse();
        HttpOptions options = HttpOptions.defaults();

        @Override
        public HttpResponse send(HttpRequest request) {
            this.captured = request;
            // A transport asks for the body before it answers, so this one does too: a request the
            // module cannot spell fails here, the way it would fail on the way out. What it wrote
            // is kept, so the tests assert what actually went out rather than what the request
            // looks like once the answer has been folded into it.
            try {
                sent = new ByteArrayOutputStream();
                request.getBody().writeTo(sent);
            } catch (IOException e) {
                throw new SynapseException("the request body could not be written", e);
            }
            canned.setOptions(HttpOptions.effective(request.getOptions(), options));
            return canned;
        }
    }

    /** The completed response a streamed answer has to add up to, used by the blocking call as well. */
    private static final String COMPLETED_RESPONSE = "{\"id\":\"resp_1\",\"model\":\"gpt-test\","
            + "\"status\":\"completed\",\"created_at\":1700000000,"
            + "\"output\":[{\"type\":\"message\",\"id\":\"msg_1\","
            + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi there\"}]}],"
            + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";

    private StubHttpClient stub;
    private JacksonJsonCodec codec;
    private OpenAiResponsesChatClient client;

    @BeforeEach
    void setUp() {
        stub = new StubHttpClient();
        codec = new JacksonJsonCodec(JsonMapper.builder());
        OpenAiConfig config = new OpenAiConfig();
        config.setApiKey("sk-test");
        client = new OpenAiResponsesChatClient(stub, codec, config);
    }

    @Test
    void systemMessageBecomesInstructions() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.systemMessage("You are helpful.");
        request.addUserMessage("Hello");

        client.chat(request);

        assertEquals("POST", stub.captured.getMethod());
        assertEquals("https://api.openai.com/v1/responses", stub.captured.getUrl());
        assertEquals(List.of("Bearer sk-test"), stub.captured.getHeaders().get("Authorization"));

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        assertEquals("gpt-test", wire.get("model"));
        // The framing sits outside the turn sequence: it goes out under the field this protocol
        // fixes for it, not as an item the model would read as someone's turn.
        assertEquals("You are helpful.", wire.get("instructions"));
        List<Map<String, Object>> input = inputOf(wire);
        assertEquals(1, input.size());
        assertEquals(ChatRole.USER.value(), input.get(0).get("role"));
        assertEquals("Hello", input.get(0).get("content"));
    }

    @Test
    void chainAnchorSkipsHistory() {
        stubCompletion();
        ChatRequest withoutAnchor = requestWithModel();
        withoutAnchor.addHistoryMessage(ChatMessage.user("earlier"));
        withoutAnchor.addPendingMessage(ChatMessage.user("Hello"));

        client.chat(withoutAnchor);
        // No chain yet: the server holds nothing, so the whole conversation goes out.
        assertEquals(2, inputOf(parseCaptured()).size());

        stubCompletion();
        ChatRequest anchored = requestWithModel();
        anchored.addHistoryMessage(ChatMessage.user("earlier"));
        anchored.addPendingMessage(ChatMessage.user("Hello"));
        anchored.getOptions().getExtras().put("previous_response_id", "resp_prev");

        client.chat(anchored);
        // With the anchor the server already holds the history, so only what is new goes out.
        List<Map<String, Object>> input = inputOf(parseCaptured());
        assertEquals(1, input.size());
        assertEquals("Hello", input.get(0).get("content"));
    }

    @Test
    void foldAnswerNamesChainAnchor() {
        ChatRequest request = requestWithModel();
        ChatResponse answer = new ChatResponse();
        answer.setId("resp_next");

        client.continueWith(request, answer);

        // The answer's turn joins the history — the chain holds it already — and its id becomes
        // the response the next call names.
        assertEquals(1, request.getHistoryMessages().size());
        assertEquals("resp_next",
                request.getOptions().getExtras().get(ResponsesWriter.PREVIOUS_RESPONSE_ID));
    }

    @Test
    void answerWithoutIdCannotAdvance() {
        ChatRequest request = requestWithModel();
        request.getOptions().getExtras().put(ResponsesWriter.PREVIOUS_RESPONSE_ID, "resp_old");
        request.addPendingMessage(ChatMessage.user("sent"));
        ChatResponse answer = new ChatResponse();

        client.continueWith(request, answer);

        // An id is what the chain would move to, and this answer carries none: the anchor stays on
        // the response it names, and the answer joins the pending messages — with the anchor
        // pointing at the older response, anything archived would be skipped on the next call.
        assertEquals("resp_old", request.getOptions().getExtras().get(ResponsesWriter.PREVIOUS_RESPONSE_ID));
        assertEquals(2, request.getPendingMessages().size());
        assertSame(answer.getMessage(), request.getPendingMessages().get(1));
        assertTrue(request.getHistoryMessages().isEmpty());
    }

    @Test
    void storePrecedence() {
        stubCompletion();
        client.chat(requestWithModel());
        // Nothing set anywhere: the member stays off the wire and the endpoint's own default —
        // keep — stands.
        assertFalse(parseCaptured().containsKey(ResponsesWriter.STORE));

        OpenAiConfig configured = new OpenAiConfig();
        configured.setApiKey("sk-test");
        configured.setStoreResponses(false);
        client.setConfig(configured);
        stubCompletion();
        client.chat(requestWithModel());
        assertEquals(Boolean.FALSE, parseCaptured().get(ResponsesWriter.STORE));

        stubCompletion();
        ChatRequest overridden = requestWithModel();
        overridden.getOptions().getExtras().put(ResponsesWriter.STORE, true);
        client.chat(overridden);
        // The request's own member wins with no special-casing: the extras merge over what the
        // config wrote.
        assertEquals(Boolean.TRUE, parseCaptured().get(ResponsesWriter.STORE));
    }

    @Test
    void unkeptAnswerStaysPending() {
        ChatRequest request = requestWithModel();
        request.getOptions().getExtras().put(ResponsesWriter.STORE, false);
        request.getOptions().getExtras().put(ResponsesWriter.PREVIOUS_RESPONSE_ID, "resp_old");
        request.addPendingMessage(ChatMessage.user("sent"));
        ChatResponse answer = new ChatResponse();
        answer.setId("resp_next");

        client.continueWith(request, answer);

        // The anchor did not move, so the next call sends only the pending messages: anything
        // archived here would be skipped while the chain still points at the older response. The
        // id cannot become the new anchor either — an answer the endpoint does not keep cannot be
        // chained on.
        assertEquals("resp_old", request.getOptions().getExtras().get(ResponsesWriter.PREVIOUS_RESPONSE_ID));
        assertEquals(2, request.getPendingMessages().size());
        assertSame(answer.getMessage(), request.getPendingMessages().get(1));
        assertTrue(request.getHistoryMessages().isEmpty());
    }

    @Test
    void unkeptAnswerStillArchives() {
        ChatRequest request = requestWithModel();
        request.getOptions().getExtras().put(ResponsesWriter.STORE, false);
        request.addPendingMessage(ChatMessage.user("sent"));
        ChatResponse answer = new ChatResponse();
        answer.setId("resp_next");

        client.continueWith(request, answer);

        // Nothing is chained, so the whole conversation is re-sent anyway: recording it here keeps
        // historyMessages meaningful at no cost. The id is not written as the anchor — a response
        // the endpoint does not keep cannot be chained on.
        assertEquals(2, request.getHistoryMessages().size());
        assertTrue(request.getPendingMessages().isEmpty());
        assertFalse(request.getOptions().getExtras().contains(ResponsesWriter.PREVIOUS_RESPONSE_ID));
    }

    @Test
    void reasoningLevelNestedUnderReasoning() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().setReasoningEffort("extreme");

        client.chat(request);

        // The level is one object deep here, and no protocol fixes the set of levels: a value this
        // library has never heard of is the endpoint's to judge rather than ours to refuse.
        assertEquals(Map.of("effort", "extreme"), parseCaptured().get("reasoning"));
    }

    @Test
    void toolDefinitionsGoOutFlat() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather",
                new JsonSchemaBuilder().setType("object")
                        .setProperties(Map.of("city", new JsonSchemaBuilder().setType("string").build()))
                        .build(),
                true, null);
        request.getTools().add(new ManualTool(tool));
        request.getOptions().setToolChoice(ChatOptions.TOOL_CHOICE_TOOL);
        request.getOptions().setToolChoiceName("get_weather");

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) wire.get("tools");
        assertEquals(1, tools.size());
        // The function's own fields sit at the top level of the tool object in this protocol, not
        // nested under a "function" member the way chat completions spells them.
        assertEquals("function", tools.get(0).get("type"));
        assertEquals("get_weather", tools.get(0).get("name"));
        assertEquals("Fetches weather", tools.get(0).get("description"));
        assertEquals(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))),
                tools.get(0).get("parameters"));
        assertEquals(Boolean.TRUE, tools.get(0).get("strict"));
        assertNull(tools.get(0).get("function"));
        assertEquals(Map.of("type", "function", "name", "get_weather"), wire.get("tool_choice"));
    }

    @Test
    void jsonSchemaFormatBecomesText() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatResponseFormat format = request.getOptions().getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("answer");
        format.setDescription("The answer, as JSON");
        format.setSchema(new JsonSchemaBuilder().setType("object").build());
        format.setStrict(true);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) wire.get("text");
        @SuppressWarnings("unchecked")
        Map<String, Object> written = (Map<String, Object>) text.get("format");
        // The format is nested under "text", and the schema's members sit beside its type rather
        // than in a "json_schema" object of their own.
        assertEquals("json_schema", written.get("type"));
        assertEquals("answer", written.get("name"));
        assertEquals("The answer, as JSON", written.get("description"));
        assertEquals(Map.of("type", "object"), written.get("schema"));
        assertEquals(Boolean.TRUE, written.get("strict"));
    }

    @Test
    void pathStopsAtModelledFormat() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatResponseFormat format = request.getOptions().getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("answer");
        request.getOptions().getExtras().put(List.of("text", "format", "strict"), true);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) wire.get("text");
        @SuppressWarnings("unchecked")
        Map<String, Object> written = (Map<String, Object>) text.get("format");
        // The format carries extras of its own, so the request's reach ends at the text object: the
        // path does not land, and the members the format models stand as this module wrote them.
        assertEquals("json_schema", written.get("type"));
        assertEquals("answer", written.get("name"));
        assertFalse(written.containsKey("strict"));
    }

    @Test
    void extrasReplaceTextWhole() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().getResponseFormat().setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        request.getOptions().getExtras().put("text", Map.of("format", Map.of("type", "text")));

        client.chat(request);

        // The entry names the text object whole, so it stands in its place rather than reaching into it.
        assertEquals(Map.of("format", Map.of("type", "text")), parseCaptured().get("text"));
    }

    @Test
    void toolCallRoundTrips() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                + "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\","
                + "\"status\":\"completed\"}]}");

        ChatResponse answer = client.chat(requestWithModel());

        assertEquals(ChatFinishReason.TOOL_CALLS, answer.getFinishReason());
        assertEquals(1, answer.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, answer.getMessage().getParts().get(0));
        assertEquals("call_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // The call goes back as the top-level item it came from, and the id the response gave it
        // rides in the part's extras all the way there.
        assertEquals(List.of(Map.of("type", "function_call", "call_id", "call_1", "name", "get_weather",
                "arguments", "{\"city\":\"Paris\"}", "id", "fc_1", "status", "completed")),
                inputOf(parseCaptured()));
    }

    @Test
    void toolResultAsCallOutput() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        // Marked as a failure, deliberately: this protocol has no member for it, so the flag is
        // left unsent and the item is exactly what a successful result would produce. Anthropic has
        // is_error for it; this protocol does not, and nothing else would notice the decision being
        // flipped.
        request.addPendingMessage(ChatMessage.of(ChatRole.USER.value(),
                new ToolResultPart("call_1", "get_weather", List.of(new TextPart("sunny")), true)));

        client.chat(request);

        // A tool result is not a message of its own in this protocol: it is an item answering the
        // call, with no role to say where it belongs.
        assertEquals(List.of(Map.of("type", "function_call_output", "call_id", "call_1", "output", "sunny")),
                inputOf(parseCaptured()));
    }

    @Test
    void toolResultGroupFlattensLikeAMessage() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.of(ChatRole.USER.value(),
                new ToolResultPart("call_1", "get_weather",
                        new ListPart(new TextPart("sun"), new TextPart("ny")))));

        client.chat(request);

        // A group inside a tool result goes out as the parts it holds, exactly as one at the top of
        // a message does: this protocol has no layer to hold one part inside another.
        assertEquals(List.of(Map.of("type", "function_call_output", "call_id", "call_1", "output", "sunny")),
                inputOf(parseCaptured()));
    }

    @Test
    void emptyMessageContributesNoItem() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.of(ChatRole.USER.value()));

        client.chat(request);

        // A message with nothing in it says nothing, so it makes no item at all.
        assertEquals(List.of(), inputOf(parseCaptured()));
    }

    @Test
    void emptyTextContributesNoItem() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.of(ChatRole.USER.value(), new TextPart("")));

        client.chat(request);

        // Text that says nothing leaves the message with nothing to contribute, so it makes no item.
        assertEquals(List.of(), inputOf(parseCaptured()));
    }

    @Test
    void emptyReasoningMakesNoItem() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.of(ChatRole.ASSISTANT.value(),
                new ReasoningPart(null, null), new TextPart("42")));

        client.chat(request);

        // Reasoning carrying neither text nor a field of its own is no item, so only the text goes out.
        assertEquals(List.of(Map.of("role", "assistant", "content", "42")), inputOf(parseCaptured()));
    }

    @Test
    void keptPartCarriesMessageFields() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.builder()
                .role(ChatRole.USER.value())
                .part(RawPart.of(Map.of("type", "web_search_call", "id", "ws_1")))
                .extras(new ProviderExtras().put("option", "o"))
                .build());

        client.chat(request);

        // The message's own fields have nowhere else to go, so the item its kept-whole part becomes
        // carries them beside the part's members.
        assertEquals(List.of(Map.of("type", "web_search_call", "id", "ws_1", "option", "o")),
                inputOf(parseCaptured()));
    }

    @Test
    void emptyMessageWithFieldsOfItsOwnStillBecomesAnItem() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.addPendingMessage(ChatMessage.builder()
                .role(ChatRole.USER.value())
                .extras(new ProviderExtras().put("option", "o"))
                .build());

        client.chat(request);

        // The fields belong to this message and have nowhere else to go, so it becomes the one item
        // that carries them even with nothing else in it.
        assertEquals(List.of(Map.of("role", "user", "option", "o")), inputOf(parseCaptured()));
    }

    @Test
    void emptySystemMessageWritesNoInstructions() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.setSystemMessage(ChatMessage.of(ChatRole.USER.value()));
        request.addPendingMessage(ChatMessage.user("hi"));

        client.chat(request);

        // The framing is the system message's text, and a message carrying none has nothing to frame
        // with: the member is left out rather than sent empty.
        assertFalse(parseCaptured().containsKey("instructions"));
    }

    @Test
    void functionCallOmitsUnsetMembers() {
        stubCompletion();
        ChatRequest request = requestWithModel();
        // A call whose members the provider left out: what is not set is not written, so nothing goes
        // out as a JSON null for the endpoint to refuse.
        request.addPendingMessage(ChatMessage.of(ChatRole.ASSISTANT.value(), new ToolCallPart(null, null, null)));

        client.chat(request);

        assertNoNullValues(parseCaptured());
    }

    @Test
    void reasoningKeepsSummaryEncrypted() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"opaque\","
                + "\"status\":\"completed\",\"summary\":[{\"type\":\"summary_text\","
                + "\"text\":\"weighing it up\"}]}]}");

        ChatResponse answer = client.chat(requestWithModel());

        assertEquals(1, answer.getMessage().getParts().size());
        ReasoningPart reasoning = assertInstanceOf(ReasoningPart.class, answer.getMessage().getParts().get(0));
        assertEquals("weighing it up", reasoning.getText());
        assertEquals("rs_1", reasoning.getExtras().get("id"));
        assertEquals("opaque", reasoning.getExtras().get("encrypted_content"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // The summary is written back as the summary it was read from, and the identity and the
        // encrypted companion the provider requires are still on the item.
        assertEquals(List.of(Map.of("type", "reasoning",
                "summary", List.of(Map.of("type", "summary_text", "text", "weighing it up")),
                "id", "rs_1", "encrypted_content", "opaque", "status", "completed")),
                inputOf(parseCaptured()));
    }

    @Test
    void rawPartKeptWhole() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"visible\"},"
                + "{\"type\":\"audio\",\"id\":\"a_1\"}]}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // The shared model has no part for the element, so it rides as itself, in the place it held
        // among the item's content entries.
        ListPart item = assertInstanceOf(ListPart.class, answer.getMessage().getParts().get(0));
        assertEquals(2, item.getSubParts().size());
        RawPart kept = assertInstanceOf(RawPart.class, item.getSubParts().get(1));
        assertEquals("audio", kept.members().get("type"));
        assertEquals("a_1", kept.members().get("id"));
    }

    @Test
    void rawPartReplayedWhole() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"visible\"},"
                + "{\"type\":\"audio\",\"id\":\"a_1\"}]}]}");
        ChatResponse answer = client.chat(requestWithModel());

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // The turn goes back as the item it was, the element read whole included, in the place it
        // held — or the provider is told a turn that never happened.
        assertEquals(List.of(Map.of("role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", "visible"),
                        Map.of("type", "audio", "id", "a_1")),
                "id", "msg_1", "status", "completed")),
                inputOf(parseCaptured()));
    }

    @Test
    void tokenLimitNameUsageNormalised() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":7,\"total_tokens\":18,"
                + "\"input_tokens_details\":{\"cached_tokens\":3},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":4}}}");

        ChatRequest request = requestWithModel();
        request.getOptions().setMaxOutputTokens(64);

        ChatResponse answer = client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(64, wire.get("max_output_tokens"));
        // This endpoint fixes the name of the limit, so the chat-completions spelling is not sent
        // beside it however the family configuration was set.
        assertFalse(wire.containsKey("max_completion_tokens"));

        assertEquals(Integer.valueOf(11), answer.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), answer.getUsage().getOutputTokens());
        assertEquals(Integer.valueOf(3), answer.getUsage().getCachedInputTokens());
        assertEquals(18, answer.getUsage().getExtras().get("total_tokens"));
        assertEquals(4, answer.getUsage().getExtras().get("output_tokens_details", "reasoning_tokens"));
    }

    @Test
    void statusDecidesFinishReason() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":[]}");
        assertEquals(ChatFinishReason.STOP, client.chat(requestWithModel()).getFinishReason());

        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}");
        assertEquals(ChatFinishReason.LENGTH, client.chat(requestWithModel()).getFinishReason());

        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\","
                + "\"arguments\":\"{}\"}]}");
        assertEquals(ChatFinishReason.TOOL_CALLS, client.chat(requestWithModel()).getFinishReason());
    }

    @Test
    void failedResponseUsesProviderDetail() {
        stubResponse("{\"id\":\"resp_1\",\"status\":\"failed\",\"output\":[],"
                + "\"error\":{\"code\":\"server_error\",\"message\":\"Something went wrong\","
                + "\"type\":\"internal\"}}");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(requestWithModel()));
        assertTrue(thrown.getMessage().contains("Something went wrong"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("internal"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("server_error"), thrown.getMessage());
    }

    @Test
    void messageItemRoundTrips() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Hi there\"}]}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // A message item is one part: its content entries are its sub-parts and its own members ride
        // in its extras, so nothing of the item boundary is lost.
        ListPart item = assertInstanceOf(ListPart.class, answer.getMessage().getParts().get(0));
        assertEquals(1, answer.getMessage().getParts().size());
        assertEquals("Hi there", assertInstanceOf(TextPart.class, item.getSubParts().get(0)).getText());
        assertEquals("msg_1", item.getExtras().get("id"));
        assertEquals("completed", item.getExtras().get("status"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // And it goes back out as the one item it was, its members and its entry beside it.
        assertEquals(List.of(Map.of("role", "assistant",
                "content", List.of(Map.of("type", "output_text", "text", "Hi there")),
                "id", "msg_1", "status", "completed")),
                inputOf(parseCaptured()));
    }

    @Test
    void twoMessageItemsStayDistinct() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"first\"}]},"
                + "{\"type\":\"message\",\"id\":\"msg_2\",\"role\":\"assistant\",\"status\":\"incomplete\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"second\"}]}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // Two items in one turn are two parts, each keeping its own identity: nothing of one collides
        // with the other, which is what a message-level bag could not say.
        List<ContentPart> parts = answer.getMessage().getParts();
        assertEquals(2, parts.size(), parts.toString());
        ListPart first = assertInstanceOf(ListPart.class, parts.get(0));
        ListPart second = assertInstanceOf(ListPart.class, parts.get(1));
        assertEquals("msg_1", first.getExtras().get("id"));
        assertEquals("msg_2", second.getExtras().get("id"));
        assertEquals("completed", first.getExtras().get("status"));
        assertEquals("incomplete", second.getExtras().get("status"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // Both go back out, each as its own item.
        List<Map<String, Object>> input = inputOf(parseCaptured());
        assertEquals(2, input.size(), input.toString());
        assertEquals("msg_1", input.get(0).get("id"));
        assertEquals("msg_2", input.get(1).get("id"));
    }

    @Test
    void unmodeledItemRoundTrips() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\","
                + "\"action\":{\"type\":\"search\",\"query\":\"weather\"}}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // An item the module does not model is one part holding its members, in the place it held
        // among the items.
        RawPart kept = assertInstanceOf(RawPart.class, answer.getMessage().getParts().get(0));
        assertEquals("web_search_call", kept.members().get("type"));
        assertEquals("ws_1", kept.members().get("id"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // And it goes back out as the item it was, every member of it.
        assertEquals(List.of(Map.of("type", "web_search_call", "id", "ws_1", "status", "completed",
                "action", Map.of("type", "search", "query", "weather"))),
                inputOf(parseCaptured()));
    }

    @Test
    void typelessItemWritesNoType() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"id\":\"ws_1\",\"status\":\"completed\"}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // An item that never spelled a type is kept whole, and the discriminator it never carried is
        // not invented as a null member of the kept item.
        RawPart kept = assertInstanceOf(RawPart.class, answer.getMessage().getParts().get(0));
        assertFalse(kept.members().containsKey("type"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // And it goes back out as it arrived, with no type member.
        assertEquals(List.of(Map.of("id", "ws_1", "status", "completed")), inputOf(parseCaptured()));
    }

    @Test
    void nonListContentKeptWhole() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":\"plain text\"}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // A content member that is not the entry array this module models is kept whole under its own
        // name, rather than dropped from the item.
        ListPart item = assertInstanceOf(ListPart.class, answer.getMessage().getParts().get(0));
        assertEquals("plain text", item.getExtras().get("content"));

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // And it goes back out as the value it was, in the place the protocol spells it.
        assertEquals(List.of(Map.of("role", "assistant", "content", "plain text",
                "id", "msg_1", "status", "completed")), inputOf(parseCaptured()));
    }

    @Test
    void reasoningItemPrecedesReplayedAnswer() {
        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(new ChatMessage(ChatRole.ASSISTANT.value(), null,
                List.of(new ReasoningPart("weighing it up"), new TextPart("Hi there"))));
        stubCompletion();

        client.chat(replay);

        // The answer is one item however many text parts it took, and it stands where its first part
        // sits: a replay that put it in front of the reasoning would hand the model a turn in an order
        // it never wrote one in.
        List<Map<String, Object>> input = inputOf(parseCaptured());
        assertEquals(2, input.size(), input.toString());
        assertEquals("reasoning", input.get(0).get("type"));
        assertEquals("assistant", input.get(1).get("role"));
    }

    @Test
    void refusalRoundTripsAsEntry() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"id\":\"msg_9\",\"role\":\"assistant\","
                + "\"status\":\"completed\","
                + "\"content\":[{\"type\":\"refusal\",\"refusal\":\"I cannot help with that\"},"
                + "{\"type\":\"output_text\",\"text\":\"visible\"}]}]}");

        ChatResponse answer = client.chat(requestWithModel());

        // A refusal is an entry the module does not model, so it rides as the entry it is, beside the
        // text, rather than being lifted onto the item.
        ListPart item = assertInstanceOf(ListPart.class, answer.getMessage().getParts().get(0));
        RawPart refusal = assertInstanceOf(RawPart.class, item.getSubParts().get(0));
        assertEquals("I cannot help with that", refusal.members().get("refusal"));
        assertEquals("visible", assertInstanceOf(TextPart.class, item.getSubParts().get(1)).getText());

        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(answer.getMessage());
        stubCompletion();
        client.chat(replay);

        // And the turn goes back as the item it was, the refusal back where the protocol spells it.
        assertEquals(List.of(Map.of("role", "assistant",
                "content", List.of(Map.of("type", "refusal", "refusal", "I cannot help with that"),
                        Map.of("type", "output_text", "text", "visible")),
                "id", "msg_9", "status", "completed")),
                inputOf(parseCaptured()));
    }

    @Test
    void streamedAnswerMatchesBlocking() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                // The frame names its event; the payload says nothing about which event it is.
                namedFrame(OpenAiResponsesEventTypes.CREATED,
                        "{\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"in_progress\","
                                + "\"output\":[]}}"),
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA,
                        "{\"item_id\":\"msg_1\",\"output_index\":0,\"delta\":\"Hi\"}"),
                // This one carries no event name, so its type member is the only thing that says.
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                        + "\"output_index\":0,\"delta\":\" there\"}",
                // A frame this module has no shape for: the protocol's own end-of-item frame. It
                // arrives whole on its event and must stay out of the answer — a blocking response
                // has no such frame, so folding its payload would make the answer depend on how it
                // was asked for.
                namedFrame("response.output_text.done",
                        "{\"item_id\":\"msg_1\",\"output_index\":0,\"text\":\"Hi there\","
                                + "\"sequence_number\":4}"),
                namedFrame(OpenAiResponsesEventTypes.COMPLETED,
                        "{\"type\":\"response.completed\",\"sequence_number\":9,\"response\":"
                                + COMPLETED_RESPONSE + "}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        List<ChatStreamEvent> events = new java.util.ArrayList<>();
        for (ChatStreamEvent event : stream) {
            events.add(event);
        }

        assertEquals(5, events.size());
        assertEquals(OpenAiResponsesEventTypes.CREATED, events.get(0).getEventType());
        assertEquals(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA, events.get(1).getEventType());
        // The second text frame named no event, so its payload's own type names the event.
        assertEquals(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA, events.get(2).getEventType());
        assertEquals("response.output_text.done", events.get(3).getEventType());
        // The frame arrives whole on the event — its position in the stream included — even
        // though the answer keeps none of it, a blocking response having no frames to carry it.
        assertEquals("msg_1", events.get(1).getExtras().get("item_id"));
        assertEquals(0, events.get(1).getExtras().get("output_index"));
        assertEquals("Hi there", events.get(3).getExtras().get("text"));
        // The frame that carries the whole response keeps its own members too, beside the
        // response's: an application reading the stream gets the frame as the provider sent it.
        assertEquals("response.completed", events.get(4).getExtras().get("type"));
        assertEquals(9, events.get(4).getExtras().get("sequence_number"));
        assertEquals(1700000000, events.get(4).getExtras().get("created_at"));
        // The events are snapshots: the answer is assembled from copies, so a delta event an
        // application kept still carries only the fragment it delivered.
        assertEquals("Hi", textOf(events.get(1)));
        assertEquals(" there", textOf(events.get(2)));

        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(ChatRole.ASSISTANT.value(), aggregated.getMessage().getRole());
        assertEquals(1, aggregated.getMessage().getParts().size());
        ListPart item = assertInstanceOf(ListPart.class, aggregated.getMessage().getParts().get(0));
        assertEquals("Hi there", assertInstanceOf(TextPart.class, item.getSubParts().get(0)).getText());

        stubResponse(COMPLETED_RESPONSE);
        ChatResponse blocking = client.chat(requestWithModel());

        assertEquals(blocking.getMessage().getRole(), aggregated.getMessage().getRole());
        assertEquals(blocking.getMessage().getParts().toString(), aggregated.getMessage().getParts().toString());
        assertEquals(blocking.getFinishReason(), aggregated.getFinishReason());
        assertEquals(blocking.getId(), aggregated.getId());
        assertEquals(blocking.getModel(), aggregated.getModel());
        assertEquals(blocking.getUsage().getInputTokens(), aggregated.getUsage().getInputTokens());
        assertEquals(blocking.getUsage().getOutputTokens(), aggregated.getUsage().getOutputTokens());
        // The response's own unmodelled member reaches the answer, and the frames' bookkeeping does
        // not: an answer carrying the kind of frame it was streamed in, the item a frame spoke for,
        // or a sequence number, would be the frame leaking into it.
        assertEquals(1700000000, aggregated.getExtras().get("created_at"));
        assertFalse(aggregated.getExtras().rawMap().containsKey("type"));
        assertFalse(aggregated.getExtras().rawMap().containsKey("text"));
        assertFalse(aggregated.getExtras().rawMap().containsKey("item_id"));
        assertFalse(aggregated.getExtras().rawMap().containsKey("sequence_number"));
        assertEquals(blocking.getExtras(), aggregated.getExtras());
    }

    @Test
    void bodyStopsEarlyFailsTruncated() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                namedFrame(OpenAiResponsesEventTypes.CREATED,
                        "{\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"in_progress\","
                                + "\"output\":[]}}"),
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA,
                        "{\"item_id\":\"msg_1\",\"output_index\":0,\"delta\":\"Hi\"}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertEquals(OpenAiResponsesEventTypes.CREATED, events.next().getEventType());
        assertEquals("Hi", textOf(events.next()));

        // A body that ends before the frame that closes the answer is a connection dropped
        // mid-answer, not an answer — the same line the completions stream draws at [DONE].
        assertThrows(SynapseException.class, events::hasNext);
        // What arrived before the cut is still the caller's: the failure reports the answer,
        // it does not discard it.
        ListPart item = assertInstanceOf(ListPart.class,
                stream.aggregatedResponse().getMessage().getParts().get(0));
        assertEquals("Hi", assertInstanceOf(TextPart.class, item.getSubParts().get(0)).getText());
        // No frame opened the item, so there are no members to carry — and the frame's own
        // bookkeeping does not stand in for them.
        assertNull(item.getExtras());
    }

    @Test
    void announcedItemKeepsItsMembersNotTheFrames() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED,
                        "{\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\","
                                + "\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}"),
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA,
                        "{\"item_id\":\"msg_1\",\"output_index\":0,\"delta\":\"Hi\"}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertEquals(OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED, events.next().getEventType());
        assertEquals("Hi", textOf(events.next()));

        // A body that stops before the frame that closes the answer leaves the caller the turn the
        // fragments built — and it carries the item's own members, not the frame's bookkeeping: what
        // the item was called and where it sat on the wire belong to the stream, not to the answer.
        assertThrows(SynapseException.class, events::hasNext);
        ListPart item = assertInstanceOf(ListPart.class,
                stream.aggregatedResponse().getMessage().getParts().get(0));
        assertEquals("Hi", assertInstanceOf(TextPart.class, item.getSubParts().get(0)).getText());
        assertEquals("msg_1", item.getExtras().get("id"));
        assertEquals("in_progress", item.getExtras().get("status"));
        assertNull(item.getExtras().get("item_id"));
        assertNull(item.getExtras().get("output_index"));
    }

    @Test
    void lateOpeningFrameKeepsFragments() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA,
                        "{\"item_id\":\"msg_1\",\"output_index\":0,\"delta\":\"Hi\"}"),
                // The whole response this frame carries has no output yet: replacing the turn
                // with it would wipe the fragment above — an opening frame announces the answer,
                // it does not speak for it.
                namedFrame(OpenAiResponsesEventTypes.IN_PROGRESS,
                        "{\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"in_progress\","
                                + "\"output\":[]}}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertEquals("Hi", textOf(events.next()));
        events.next();

        ListPart item = assertInstanceOf(ListPart.class,
                stream.aggregatedResponse().getMessage().getParts().get(0));
        assertEquals("Hi", assertInstanceOf(TextPart.class, item.getSubParts().get(0)).getText());
    }

    @Test
    void deltasFoldIntoAnnouncedCall() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                // The frame that announces a call names it; the frames that spell its arguments
                // name the item instead, which is the only thing that ties the two together.
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED,
                        "{\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\","
                                + "\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                namedFrame(OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA,
                        "{\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}"),
                namedFrame(OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA,
                        "{\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"\\\"SF\\\"}\"}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        events.next();
        events.next();
        events.next();

        List<ContentPart> parts = stream.aggregatedResponse().getMessage().getParts();
        assertEquals(1, parts.size(), parts.toString());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, parts.get(0));
        assertEquals("call_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        assertEquals("{\"city\":\"SF\"}", call.getArgumentsJson());
    }

    @Test
    void positionMatchesDeltasWithoutId() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                // Two calls are announced, so a fragment that says only where its item sits has to
                // be matched by that position: the call it belongs to is not the one opened last.
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED,
                        "{\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\","
                                + "\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                namedFrame(OpenAiResponsesEventTypes.OUTPUT_ITEM_ADDED,
                        "{\"output_index\":1,\"item\":{\"id\":\"fc_2\",\"type\":\"function_call\","
                                + "\"call_id\":\"call_2\",\"name\":\"lookup\",\"arguments\":\"\"}}"),
                // An endpoint that spells its arguments without saying which item they belong to
                // still says where the item sits, and that is what they are matched by.
                namedFrame(OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA,
                        "{\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}"),
                namedFrame(OpenAiResponsesEventTypes.FUNCTION_CALL_ARGUMENTS_DELTA,
                        "{\"output_index\":0,\"delta\":\"\\\"SF\\\"}\"}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        events.next();
        events.next();
        events.next();
        events.next();

        List<ContentPart> parts = stream.aggregatedResponse().getMessage().getParts();
        assertEquals(2, parts.size(), parts.toString());
        ToolCallPart first = assertInstanceOf(ToolCallPart.class, parts.get(0));
        assertEquals("call_1", first.getCallId());
        assertEquals("{\"city\":\"SF\"}", first.getArgumentsJson());
        assertEquals("lookup", assertInstanceOf(ToolCallPart.class, parts.get(1)).getName());
    }

    /** A canned completed response, for the tests that only care about the request. */
    private void stubCompletion() {
        stubResponse("{\"id\":\"resp_1\",\"model\":\"gpt-test\",\"status\":\"completed\",\"output\":[]}");
    }

    private void stubResponse(String json) {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(json.getBytes(UTF_8)));
    }

    private Map<String, Object> parseCaptured() {
        try {
            // What the transport was handed, kept at send time: the request moves on once the
            // answer is folded into it, and what is asserted here is what actually went out.
            return codec.decode(stub.sent.toString(UTF_8), Map.class);
        } catch (RuntimeException e) {
            throw new AssertionError("captured wire body is not JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inputOf(Map<String, Object> wire) {
        return (List<Map<String, Object>>) wire.get("input");
    }

    /** An SSE body of the given frames, each followed by the blank line that dispatches it. */
    private static String sse(String... frames) {
        StringBuilder body = new StringBuilder();
        for (String frame : frames) {
            body.append(frame).append("\n\n");
        }
        return body.toString();
    }

    /** One named frame: its {@code event:} line and the {@code data:} line that follows it. */
    private static String namedFrame(String event, String data) {
        return "event: " + event + "\ndata: " + data;
    }

}
