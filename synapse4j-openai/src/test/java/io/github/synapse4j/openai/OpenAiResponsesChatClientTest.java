package io.github.synapse4j.openai;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.tool.ManualTool;
import io.github.synapse4j.tool.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class OpenAiResponsesChatClientTest {

    /** Captures the outgoing request and replays a canned response. */
    static class StubHttpClient implements HttpClient {

        io.github.synapse4j.http.HttpRequest captured;

        /** The body as it went out; asserted instead of a re-write of a request that has moved on. */
        ByteArrayOutputStream sent = new ByteArrayOutputStream();

        DefaultHttpResponse canned = new DefaultHttpResponse();
        io.github.synapse4j.http.HttpOptions options = io.github.synapse4j.http.HttpOptions.defaults();

        @Override
        public HttpResponse send(io.github.synapse4j.http.HttpRequest request) {
            this.captured = request;
            // A transport asks for the body before it answers, so this one does too: a request the
            // module cannot spell fails here, the way it would fail on the way out. What it wrote
            // is kept, because the request itself moves on once the exchange succeeds — the client
            // archives what it sent — and what the tests assert is what actually went out.
            try {
                sent = new ByteArrayOutputStream();
                request.getBody().writeTo(sent);
            } catch (IOException e) {
                throw new SynapseException("the request body could not be written", e);
            }
            canned.setOptions(io.github.synapse4j.http.HttpOptions.effective(request.getOptions(), options));
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
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
        OpenAiConfig config = new OpenAiConfig();
        config.setApiKey("sk-test");
        client = new OpenAiResponsesChatClient(stub, codec, config);
    }

    @Test
    void theSystemMessageGoesOutAsInstructionsAndTheRestAsInputItems() {
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
        assertEquals(ChatRole.USER, input.get(0).get("role"));
        assertEquals("Hello", input.get(0).get("content"));
    }

    @Test
    void aChainAnchorKeepsTheHistoryTheServerAlreadyHoldsOffTheWire() {
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
    void theReasoningLevelGoesOutNestedUnderReasoning() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().setReasoningEffort("extreme");

        client.chat(request);

        // The level is one object deep here, and no protocol fixes the set of levels: a value this
        // library has never heard of is the endpoint's to judge rather than ours to refuse.
        assertEquals(Map.of("effort", "extreme"), parseCaptured().get("reasoning"));
    }

    @Test
    void toolDefinitionsGoOutFlatAndANamedToolChoiceNamesTheFunction() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        tool.setStrict(true);
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
    void aJsonSchemaResponseFormatBecomesTheTextFormat() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatResponseFormat format = request.getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("answer");
        format.setDescription("The answer, as JSON");
        format.setSchema("{\"type\":\"object\"}");
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
    void aToolCallComesBackKeyedByCallIdAndReplaysAsItsItem() {
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
    void aToolResultReplaysAsATopLevelFunctionCallOutput() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatMessage results = new ChatMessage(ChatRole.TOOL);
        results.addPart(new ToolResultPart("call_1", "get_weather", false).addText("sunny"));
        request.addPendingMessage(results);

        client.chat(request);

        // A tool result is not a message of its own in this protocol: it is an item answering the
        // call, with no role to say where it belongs.
        assertEquals(List.of(Map.of("type", "function_call_output", "call_id", "call_1", "output", "sunny")),
                inputOf(parseCaptured()));
    }

    @Test
    void aReasoningItemKeepsItsSummaryAndItsEncryptedContent() {
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
    void theTokenLimitGoesOutUnderItsOwnNameAndUsageIsNormalised() {
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
    void theStatusDecidesTheFinishReason() {
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
    void aFailedResponseFailsWithTheProvidersOwnDetail() {
        stubResponse("{\"id\":\"resp_1\",\"status\":\"failed\",\"output\":[],"
                + "\"error\":{\"code\":\"server_error\",\"message\":\"Something went wrong\","
                + "\"type\":\"internal\"}}");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(requestWithModel()));
        assertTrue(thrown.getMessage().contains("Something went wrong"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("internal"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("server_error"), thrown.getMessage());
    }

    @Test
    void aStreamedAnswerFoldsIntoTheSameAnswerABlockingCallReturns() {
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
                namedFrame(OpenAiResponsesEventTypes.COMPLETED,
                        "{\"type\":\"response.completed\",\"response\":" + COMPLETED_RESPONSE + "}"))
                .getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        List<ChatStreamEvent> events = new java.util.ArrayList<>();
        for (ChatStreamEvent event : stream) {
            events.add(event);
        }

        assertEquals(4, events.size());
        assertEquals(OpenAiResponsesEventTypes.CREATED, events.get(0).getEventType());
        assertEquals(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA, events.get(1).getEventType());
        // The second text frame named no event, so its payload's own type names the event.
        assertEquals(OpenAiResponsesEventTypes.OUTPUT_TEXT_DELTA, events.get(2).getEventType());

        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(ChatRole.ASSISTANT, aggregated.getMessage().getRole());
        assertEquals(1, aggregated.getMessage().getParts().size());
        assertEquals("Hi there",
                assertInstanceOf(TextPart.class, aggregated.getMessage().getParts().get(0)).getText());

        stubResponse(COMPLETED_RESPONSE);
        ChatResponse blocking = client.chat(requestWithModel());

        assertEquals(blocking.getMessage().getRole(), aggregated.getMessage().getRole());
        assertEquals(blocking.getMessage().getParts().toString(), aggregated.getMessage().getParts().toString());
        assertEquals(blocking.getFinishReason(), aggregated.getFinishReason());
        assertEquals(blocking.getId(), aggregated.getId());
        assertEquals(blocking.getModel(), aggregated.getModel());
        assertEquals(blocking.getUsage().getInputTokens(), aggregated.getUsage().getInputTokens());
        assertEquals(blocking.getUsage().getOutputTokens(), aggregated.getUsage().getOutputTokens());
        // A member the module does not model arrives the same way too: what the blocking walk kept
        // in extras, the drained stream has folded into its own — same keys, same values.
        assertEquals(blocking.getExtras().rawMap(), aggregated.getExtras().rawMap());
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
            // What the transport was handed, kept at send time: the request itself moves on once
            // the exchange succeeds, and what is asserted here is what actually went out.
            return codec.decode(stub.sent.toString(UTF_8), Map.class);
        } catch (RuntimeException e) {
            throw new AssertionError("captured wire body is not JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inputOf(Map<String, Object> wire) {
        return (List<Map<String, Object>>) wire.get("input");
    }

    private static void assertNoNullValues(Map<String, Object> map) {
        map.forEach((key, value) -> assertNotNull(value, "wire field '" + key + "' was serialized as null"));
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

    /** A request with a model set, which is all the request-writing tests here need. */
    private static ChatRequest requestWithModel() {
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        return request;
    }

}
