package io.github.synapse4j.anthropic;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
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
import io.github.synapse4j.data.MediaPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.ReasoningPart;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseHttpException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.tool.ManualTool;
import io.github.synapse4j.tool.ToolDefinition;
import io.github.synapse4j.util.InputStreamSupplier;
import tools.jackson.databind.json.JsonMapper;

class AnthropicChatClientTest {

    /** Captures the outgoing request and replays a canned response. */
    static class StubHttpClient implements HttpClient {

        HttpRequest captured;
        DefaultHttpResponse canned = new DefaultHttpResponse();
        HttpOptions options = HttpOptions.defaults();

        @Override
        public HttpResponse send(HttpRequest request) {
            this.captured = request;
            // A transport asks for the body before it answers, so this one does too: a request the
            // module cannot spell fails here, the way it would fail on the way out.
            try {
                request.getBody().writeTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException e) {
                throw new SynapseException("the request body could not be written", e);
            }
            // A transport is where a request's options meet its own, so the response it hands back
            // carries the result: whatever the exchange was configured with is what frames its
            // event stream.
            canned.setOptions(HttpOptions.effective(request.getOptions(), options));
            return canned;
        }
    }

    /** A response body that remembers being closed, which is how a cancelled stream shows. */
    static class RecordedInputStream extends ByteArrayInputStream {

        boolean closed;

        RecordedInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private StubHttpClient stub;
    private JacksonJsonCodec codec;
    private AnthropicConfig config;
    private AnthropicChatClient client;

    @BeforeEach
    void setUp() {
        stub = new StubHttpClient();
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
        config = new AnthropicConfig();
        config.setApiKey("sk-ant-test");
        client = new AnthropicChatClient(stub, codec, config);
    }

    @Test
    void theRequestCarriesTheProtocolSpellingAndNoNulls() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_1\"",
                "[{\"type\":\"text\",\"text\":\"Hi there\"}]", "end_turn",
                "\"usage\":{\"input_tokens\":11,\"output_tokens\":7}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();
        request.addUserMessage("Hello");
        request.getOptions().setTemperature(0.5);
        request.getOptions().setTopP(0.9);

        ChatResponse response = client.chat(request);

        assertEquals("POST", stub.captured.getMethod());
        assertEquals("https://api.anthropic.com/v1/messages", stub.captured.getUrl());
        assertEquals(List.of("application/json"), stub.captured.getHeaders().get("Content-Type"));
        assertEquals(List.of("sk-ant-test"), stub.captured.getHeaders().get("x-api-key"));
        // The protocol version rides on every request, under the name the endpoint fixed for it.
        assertEquals(List.of("2023-06-01"), stub.captured.getHeaders().get("anthropic-version"));

        Map<String, Object> wire = parseCaptured();
        assertEquals("claude-test", wire.get("model"));
        assertEquals(0.5, wire.get("temperature"));
        assertEquals(64, wire.get("max_tokens"));
        assertEquals(0.9, wire.get("top_p"));
        assertNoNullValues(wire);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(1, messages.size());
        assertEquals(ChatRole.USER, messages.get(0).get("role"));
        // A message of text alone takes the plain-string content form.
        assertEquals("Hello", messages.get(0).get("content"));

        assertEquals(1, response.getMessage().getParts().size());
        TextPart text = assertInstanceOf(TextPart.class, response.getMessage().getParts().get(0));
        assertEquals("Hi there", text.getText());
        assertEquals(ChatRole.ASSISTANT, response.getMessage().getRole());
        assertEquals(ChatFinishReason.STOP, response.getFinishReason());
        assertEquals("msg_1", response.getId());
        assertEquals("claude-test", response.getModel());
        assertEquals(Integer.valueOf(11), response.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), response.getUsage().getOutputTokens());
    }

    @Test
    void aBaseUrlWithATrailingSlashDoesNotDoubleThePath() {
        stubCompletion();
        config.setBaseUrl("https://example.test/");

        client.chat(requestWithModel());

        assertEquals("https://example.test/v1/messages", stub.captured.getUrl());
    }

    @Test
    void theSystemMessageGoesOutAsTheTopLevelSystemField() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.systemMessage("You are helpful.");
        request.addUserMessage("Hello");

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        // The protocol keeps no turn for the framing: it travels as its own field, resent with
        // every call, and never as an entry of the conversation below it.
        assertEquals("You are helpful.", wire.get("system"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(1, messages.size());
        assertEquals(ChatRole.USER, messages.get(0).get("role"));
    }

    @Test
    void aTokenLimitIsRequiredAndTheExtrasBagCanSupplyIt() {
        stubCompletion();

        // The endpoint has no server-side default for the limit, so a call with no opinion on it
        // is refused by name rather than sent with a number nobody chose.
        ChatRequest without = new ChatRequest();
        without.getOptions().setModel("claude-test");
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(without));
        assertTrue(thrown.getMessage().contains("max_tokens"), thrown.getMessage());

        // The options bag is the documented way to state it once for every call.
        ChatRequest viaExtras = new ChatRequest();
        viaExtras.getOptions().setModel("claude-test");
        viaExtras.getOptions().getExtras().put("max_tokens", 42);
        client.chat(viaExtras);
        assertEquals(42, parseCaptured().get("max_tokens"));
    }

    @Test
    void toolChoiceTranslatesOntoTheProtocolShapes() {
        stubCompletion();

        ChatRequest required = requestWithModel();
        required.getOptions().setToolChoice(ChatOptions.TOOL_CHOICE_REQUIRED);
        client.chat(required);
        // The shared vocabulary says "at least one"; this protocol spells that "any", and its
        // tool_choice always takes the object form.
        assertEquals(Map.of("type", "any"), parseCaptured().get("tool_choice"));

        stubCompletion();
        ChatRequest named = requestWithModel();
        named.getOptions().setToolChoice(ChatOptions.TOOL_CHOICE_TOOL);
        named.getOptions().setToolChoiceName("get_weather");
        client.chat(named);
        assertEquals(Map.of("type", "tool", "name", "get_weather"),
                parseCaptured().get("tool_choice"));

        stubCompletion();
        // A value the provider itself understands, in the provider's own spelling, passes as it
        // stands — the protocol fixed the set of modes, and "any" is in it.
        ChatRequest providerSpelling = requestWithModel();
        providerSpelling.getOptions().setToolChoice("any");
        client.chat(providerSpelling);
        assertEquals(Map.of("type", "any"), parseCaptured().get("tool_choice"));
    }

    @Test
    void aToolChoiceModeThisProtocolCannotSpellIsLeftUnsent() {
        stubCompletion();

        // A mode outside the set the protocol fixes is left off the wire, not refused: the call
        // goes on with the endpoint's own default.
        ChatRequest unknownMode = requestWithModel();
        unknownMode.getOptions().setToolChoice("sample");
        client.chat(unknownMode);

        assertFalse(parseCaptured().containsKey("tool_choice"));
    }

    @Test
    void aContradictoryToolChoiceIsRefused() {
        // A name beside a mode that names no tool is half a requirement.
        ChatRequest strayName = requestWithModel();
        strayName.getOptions().setToolChoice(ChatOptions.TOOL_CHOICE_AUTO);
        strayName.getOptions().setToolChoiceName("get_weather");
        assertThrows(SynapseException.class, () -> client.chat(strayName));

        // ... and so is a tool mode that names nothing.
        ChatRequest noName = requestWithModel();
        noName.getOptions().setToolChoice(ChatOptions.TOOL_CHOICE_TOOL);
        assertThrows(SynapseException.class, () -> client.chat(noName));
    }

    @Test
    void aStrictToolGoesOutWithItsSchemaParsed() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        tool.setStrict(true);
        request.getTools().add(new ManualTool(tool));

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) wire.get("tools");
        assertEquals(1, tools.size());
        Map<String, Object> sent = tools.get(0);
        assertEquals("get_weather", sent.get("name"));
        assertEquals("Fetches weather", sent.get("description"));
        // The schema string left the shared model as text and reaches the wire as a parsed object,
        // and the enforcement flag is a top-level member beside it — this protocol carries strict.
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) sent.get("input_schema");
        assertEquals("object", schema.get("type"));
        assertEquals(Map.of("type", "string"),
                ((Map<?, ?>) schema.get("properties")).get("city"));
        assertEquals(Boolean.TRUE, sent.get("strict"));
    }

    @Test
    void aReplayedToolCallGoesOutAsAToolUseBlock() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatMessage replay = new ChatMessage(ChatRole.ASSISTANT);
        replay.addPart(new ToolCallPart("toolu_1", "get_weather", "{\"city\":\"Paris\"}"));
        request.addPendingMessage(replay);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals("assistant", messages.get(0).get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(1, content.size());
        assertEquals(Map.of("type", "tool_use", "id", "toolu_1", "name", "get_weather",
                "input", Map.of("city", "Paris")), content.get(0));
    }

    @Test
    void toolResultsBecomeToolResultBlocksInAUserMessage() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatMessage results = new ChatMessage(ChatRole.TOOL);
        results.addPart(new ToolResultPart("toolu_1", "get_weather", false).addText("sunny"));
        results.addPart(new ToolResultPart("toolu_2", "get_time", true).addText("boom"));
        request.addPendingMessage(results);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(1, messages.size());
        // The protocol has no tool role: results travel inside a user message, all of them as
        // blocks of that one message.
        assertEquals(ChatRole.USER, messages.get(0).get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(2, content.size());
        assertEquals(Map.of("type", "tool_result", "tool_use_id", "toolu_1", "content", "sunny"),
                content.get(0));
        // Only a failure is stated: false is the protocol's own default.
        assertEquals(Map.of("type", "tool_result", "tool_use_id", "toolu_2", "content", "boom",
                "is_error", true), content.get(1));
    }

    @Test
    void thinkingBlocksRoundTrip() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_2\"",
                "[{\"type\":\"thinking\",\"thinking\":\"weigh it up\",\"signature\":\"sig_abc\"},"
                        + "{\"type\":\"text\",\"text\":\"42\"}]",
                "end_turn", "\"usage\":{\"input_tokens\":9,\"output_tokens\":4}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();
        ChatResponse response = client.chat(request);

        // Reasoning is its own part rather than text, and the signature — which the endpoint
        // demands back verbatim — rides in the part's extras under the protocol's name.
        List<ContentPart> parts = response.getMessage().getParts();
        assertEquals(2, parts.size());
        ReasoningPart reasoning = assertInstanceOf(ReasoningPart.class, parts.get(0));
        assertEquals("weigh it up", reasoning.getText());
        assertEquals("sig_abc", reasoning.getExtras().get("signature"));
        assertEquals("42", assertInstanceOf(TextPart.class, parts.get(1)).getText());

        // Replaying the turn sends the block back as the block it was.
        stubCompletion();
        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(response.getMessage());
        client.chat(replay);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) parseCaptured().get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(Map.of("type", "thinking", "thinking", "weigh it up", "signature", "sig_abc"),
                content.get(0));
        assertEquals(Map.of("type", "text", "text", "42"), content.get(1));
    }

    @Test
    void effortAndSchemaFormatShareOneOutputConfig() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().setReasoningEffort("high");
        ChatResponseFormat format = request.getOptions().getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setSchema("{\"type\":\"object\"}");

        client.chat(request);

        // The reasoning level and the answer's shape are one protocol object — two members of the
        // document would leave only the last one standing.
        Map<String, Object> wire = parseCaptured();
        assertEquals(Map.of("effort", "high",
                "format", Map.of("type", "json_schema", "schema", Map.of("type", "object"))),
                wire.get("output_config"));
    }

    @Test
    void aResponseFormatTheProtocolCannotCarryIsRefused() {
        stubCompletion();

        // Only a schema-shaped answer has a member here; prose is what the endpoint answers with
        // when no format is asked for, and "any JSON" would have to be invented to be written.
        ChatRequest anyJson = requestWithModel();
        anyJson.getOptions().getResponseFormat().setType(ChatResponseFormat.TYPE_JSON);
        SynapseException thrown = assertThrows(SynapseException.class,
                () -> client.chat(anyJson));
        assertTrue(thrown.getMessage().contains("json"), thrown.getMessage());
    }

    @Test
    void aResponseFormatMemberTheProtocolLacksIsLeftUnsent() {
        stubCompletion();

        // The protocol's format carries no name, description or enforcement flag; those are left
        // off the wire, and the schema the protocol can honour still goes out.
        ChatRequest described = requestWithModel();
        ChatResponseFormat format = described.getOptions().getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setSchema("{\"type\":\"object\"}");
        format.setName("person");
        format.setDescription("The answer, as JSON");
        format.setStrict(false);
        client.chat(described);

        @SuppressWarnings("unchecked")
        Map<String, Object> outputConfig = (Map<String, Object>) parseCaptured().get("output_config");
        @SuppressWarnings("unchecked")
        Map<String, Object> formatWire = (Map<String, Object>) outputConfig.get("format");
        assertEquals(Map.of("type", "json_schema", "schema", Map.of("type", "object")), formatWire);
    }

    @Test
    void anImageGoesOutAsAnImageBlock() {
        stubCompletion();

        ChatRequest uriRequest = requestWithModel();
        ChatMessage withUri = new ChatMessage(ChatRole.USER);
        withUri.addPart(new MediaPart("image/png", "https://example.test/cat.png", null, null));
        uriRequest.addPendingMessage(withUri);
        client.chat(uriRequest);

        // A URL is passed through as it stands: the provider fetches it.
        assertEquals(Map.of("type", "image", "source",
                Map.of("type", "url", "url", "https://example.test/cat.png")),
                contentOf(parseCaptured()).get(0));

        stubCompletion();
        byte[] bytes = { (byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff, 0x7f };
        ChatRequest inlineRequest = requestWithModel();
        ChatMessage inlined = new ChatMessage(ChatRole.USER);
        inlined.addPart(new MediaPart("image/png", null, InputStreamSupplier.of(bytes), null));
        inlineRequest.addPendingMessage(inlined);
        client.chat(inlineRequest);

        // A payload is inlined as base64, and the source object is what declares its type.
        Map<String, Object> source = sourceOf(contentOf(parseCaptured()).get(0));
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        String data = (String) source.get("data");
        assertArrayEquals(bytes, Base64.getDecoder().decode(data));
    }

    @Test
    void mediaThisProtocolCannotCarryIsRejected() {
        stubCompletion();

        ChatRequest audio = requestWithModel();
        ChatMessage audioMessage = new ChatMessage(ChatRole.USER);
        audioMessage.addPart(new MediaPart("audio/wav", null, InputStreamSupplier.of(new byte[] { 1 }), null));
        audio.addPendingMessage(audioMessage);
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(audio));
        assertTrue(thrown.getMessage().contains("audio/wav"), thrown.getMessage());

        stubCompletion();
        ChatRequest neither = requestWithModel();
        ChatMessage neitherMessage = new ChatMessage(ChatRole.USER);
        neitherMessage.addPart(new MediaPart("image/png", null, null, null));
        neither.addPendingMessage(neitherMessage);
        thrown = assertThrows(SynapseException.class, () -> client.chat(neither));
        assertTrue(thrown.getMessage().contains("neither uri nor source"), thrown.getMessage());

        stubCompletion();
        ChatRequest untyped = requestWithModel();
        ChatMessage untypedMessage = new ChatMessage(ChatRole.USER);
        untypedMessage.addPart(new MediaPart(null, null, InputStreamSupplier.of(new byte[] { 1 }), null));
        untyped.addPendingMessage(untypedMessage);
        thrown = assertThrows(SynapseException.class, () -> client.chat(untyped));
        assertTrue(thrown.getMessage().contains("mediaType"), thrown.getMessage());
    }

    @Test
    void unsupportedPartsFailLoudly() {
        ChatRequest request = requestWithModel();
        ChatMessage message = new ChatMessage(ChatRole.USER);
        message.addPart(new UnmodelledPart());
        request.addPendingMessage(message);

        assertThrows(SynapseException.class, () -> client.chat(request));
    }

    @Test
    void stopReasonsMapToTheSharedVocabularyAndTheRestPassesThrough() {
        // The three the shared vocabulary names outright.
        assertEquals(ChatFinishReason.STOP, finishReasonOf("end_turn"));
        assertEquals(ChatFinishReason.LENGTH, finishReasonOf("max_tokens"));
        assertEquals(ChatFinishReason.TOOL_CALLS, finishReasonOf("tool_use"));
        // A refusal is this protocol's word for an answer the provider cut short on purpose.
        assertEquals(ChatFinishReason.CONTENT_FILTER, finishReasonOf("refusal"));
        // pause_turn means nothing the vocabulary says, so it arrives as it stands — renaming it
        // would say more than the protocol did.
        assertEquals("pause_turn", finishReasonOf("pause_turn"));
    }

    @Test
    void usageCountsNormalizeToTheSharedShape() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_3\"",
                "[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn",
                "\"usage\":{\"input_tokens\":2048,\"cache_read_input_tokens\":1800,"
                        + "\"cache_creation_input_tokens\":248,\"output_tokens\":503,"
                        + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":248,"
                        + "\"ephemeral_1h_input_tokens\":0}}")
                .getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatResponse response = client.chat(request);

        // This endpoint splits what the request sent into three counts; the shared model takes one
        // number meaning the same thing across providers: everything sent, with the cached part as
        // a child of it rather than a sibling to add on top.
        assertEquals(Integer.valueOf(2048 + 1800 + 248), response.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(1800), response.getUsage().getCachedInputTokens());
        assertEquals(Integer.valueOf(503), response.getUsage().getOutputTokens());
        // The cache write is counted in the input total but named by no field of its own, so it
        // keeps its original name where every unmodelled count goes.
        assertEquals(248, response.getUsage().getExtras().get("cache_creation_input_tokens"));
        assertEquals(Map.of("ephemeral_5m_input_tokens", 248, "ephemeral_1h_input_tokens", 0),
                response.getUsage().getExtras().get("cache_creation"));
    }

    @Test
    void unknownFieldsStayOnTheNodeTheyCameFrom() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_4\"",
                "[{\"type\":\"text\",\"text\":\"hi\",\"citations\":"
                        + "[{\"type\":\"char_location\",\"cited_text\":\"...\"}]}]",
                "end_turn", "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}",
                ",\"stop_sequence\":\"END\",\"container\":\"cnr_1\"").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatResponse response = client.chat(request);

        // The response document is the message, so its unmodelled members stay on the answer...
        assertEquals("cnr_1", response.getExtras().get("container"));
        assertEquals("END", response.getExtras().get("stop_sequence"));
        // ... and a field of a content block stays on the block's part.
        assertEquals(1, response.getMessage().getParts().size());
        TextPart part = assertInstanceOf(TextPart.class, response.getMessage().getParts().get(0));
        assertEquals("hi", part.getText());
        assertEquals(List.of(Map.of("type", "char_location", "cited_text", "...")),
                part.getExtras().get("citations"));
    }

    @Test
    void anUnmodeledContentBlockIsKeptWhole() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_5\"",
                "[{\"type\":\"text\",\"text\":\"hi\"},"
                        + "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"weather\"}}]",
                "end_turn", "\"usage\":{\"input_tokens\":7,\"output_tokens\":2}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatResponse response = client.chat(request);

        // The shared model has no part for the block, so it rides as itself: it keeps its place in
        // the turn — after the text, where it sat in the content array — and goes back out whole.
        assertEquals(2, response.getMessage().getParts().size());
        RawContentBlock kept = assertInstanceOf(RawContentBlock.class,
                response.getMessage().getParts().get(1));
        assertEquals("server_tool_use", kept.getMembers().get("type"));
        assertEquals(Map.of("query", "weather"), kept.getMembers().get("input"));
        // The fields after it were still read, so the walk stayed in step.
        assertEquals(Integer.valueOf(7), response.getUsage().getInputTokens());
    }

    @Test
    void aServerToolsBlockComesBackOnTheNextTurnAsItArrived() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_5\"",
                "[{\"type\":\"text\",\"text\":\"hi\"},"
                        + "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"weather\"}}]",
                "end_turn", "\"usage\":{\"input_tokens\":7,\"output_tokens\":2}").getBytes(UTF_8)));

        ChatResponse response = client.chat(requestWithModel());

        // The turn continues the way an ongoing conversation continues: the answer's message joins
        // the history, and the next request sends it back — the block read whole must arrive on the
        // wire whole, in the place it held, or the provider is told a turn that never happened.
        stubCompletion();
        ChatRequest next = requestWithModel();
        client.continueWith(next, response);
        client.chat(next);

        List<Map<String, Object>> content = contentOf(parseCaptured());
        assertEquals(2, content.size());
        assertEquals(Map.of("type", "text", "text", "hi"), content.get(0));
        assertEquals(Map.of("type", "server_tool_use", "id", "srvtoolu_1", "name", "web_search",
                "input", Map.of("query", "weather")), content.get(1));
    }

    @Test
    void toolCallsComeBackAsToolUseBlocks() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_6\"",
                "[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\","
                        + "\"input\":{\"city\":\"Paris\"}}]",
                "tool_use", "\"usage\":{\"input_tokens\":11,\"output_tokens\":6}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatResponse response = client.chat(request);

        assertEquals(ChatFinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(1, response.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, response.getMessage().getParts().get(0));
        assertEquals("toolu_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        // The wire carries the input as an object; the shared model keeps the JSON text it spells.
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());
    }

    @Test
    void aTextStreamAggregatesToTheSameAnswerAsABlockingCall() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "ping",
                "{\"type\":\"ping\"}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi \"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"there\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":7}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();
        request.addUserMessage("Hello");

        ChatStream stream = client.stream(request);
        List<ChatStreamEvent> events = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            events.add(event);
        }

        // One event per frame, the keep-alive and the bracket frames included — whether they are
        // worth an event is the application's decision, not the module's.
        assertEquals(8, events.size());
        assertEquals(AnthropicEventTypes.MESSAGE_START, events.get(0).getEventType());
        assertEquals(AnthropicEventTypes.CONTENT_BLOCK_START, events.get(1).getEventType());
        assertEquals(AnthropicEventTypes.PING, events.get(2).getEventType());
        assertEquals(AnthropicEventTypes.MESSAGE_STOP, events.get(7).getEventType());
        assertEquals(Boolean.TRUE, parseCaptured().get("stream"));

        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(ChatRole.ASSISTANT, aggregated.getMessage().getRole());
        assertEquals(1, aggregated.getMessage().getParts().size());
        assertEquals("Hi there",
                assertInstanceOf(TextPart.class, aggregated.getMessage().getParts().get(0)).getText());
        assertEquals(ChatFinishReason.STOP, aggregated.getFinishReason());
        assertEquals("msg_1", aggregated.getId());
        assertEquals("claude-test", aggregated.getModel());
        // The input counts arrived with the frame that opened the answer; the output counts with
        // the frame that closed it — the assembled answer carries both.
        assertEquals(Integer.valueOf(25), aggregated.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), aggregated.getUsage().getOutputTokens());

        // The same answer asked for in one piece has to come back the same way.
        stub.canned = new DefaultHttpResponse();
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_1\"",
                "[{\"type\":\"text\",\"text\":\"Hi there\"}]", "end_turn",
                "\"usage\":{\"input_tokens\":25,\"output_tokens\":7}").getBytes(UTF_8)));
        ChatResponse blocking = client.chat(request);

        assertEquals(blocking.getMessage().getRole(), aggregated.getMessage().getRole());
        assertEquals(blocking.getMessage().getParts().toString(),
                aggregated.getMessage().getParts().toString());
        assertEquals(blocking.getFinishReason(), aggregated.getFinishReason());
        assertEquals(blocking.getId(), aggregated.getId());
        assertEquals(blocking.getModel(), aggregated.getModel());
        assertEquals(blocking.getUsage().getInputTokens(), aggregated.getUsage().getInputTokens());
        assertEquals(blocking.getUsage().getOutputTokens(), aggregated.getUsage().getOutputTokens());
        // Unmodelled fields arrive the same way too: what the blocking walk kept in extras, the
        // drained stream has folded into its own — same keys, same values, and no stream
        // bookkeeping among them.
        assertEquals(blocking.getExtras().rawMap(), aggregated.getExtras().rawMap());
    }

    @Test
    void anUnmodeledDeltaStaysOnTheEventAndOffTheAnswer() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"citations_delta\",\"citation\":{\"url\":\"https://example.test\"}}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":7}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        ChatStreamEvent delta = null;
        for (ChatStreamEvent event : stream) {
            if (AnthropicEventTypes.CONTENT_BLOCK_DELTA.equals(event.getEventType())) {
                delta = event;
            }
        }

        // A delta kind this module did not model stays on the event, whole, for the application to
        // read — and is never promoted to a member of the answer, which the blocking walk could not
        // produce and which would make the answer depend on how it was asked for.
        assertNotNull(delta);
        assertNotNull(delta.getExtras().get("delta"));
        assertFalse(stream.aggregatedResponse().getExtras().rawMap().containsKey("delta"));
    }

    @Test
    void toolInputFragmentsAccumulateIntoOneCall() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_7\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\","
                        + "\"input\":{}}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Paris\\\"}\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        int frames = 0;
        while (events.hasNext()) {
            events.next();
            frames++;
        }
        assertEquals(7, frames);

        // The input arrived as two fragments of JSON text and is whole by the time the block
        // closed: one call, the arguments a single response would have carried.
        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(ChatFinishReason.TOOL_CALLS, aggregated.getFinishReason());
        assertEquals(1, aggregated.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, aggregated.getMessage().getParts().get(0));
        assertEquals("toolu_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());

        // And what it spells goes back out as the object the endpoint reads.
        stubCompletion();
        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(aggregated.getMessage());
        client.chat(replay);
        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        Map<String, Object> block = content.get(0);
        assertEquals("tool_use", block.get("type"));
        assertEquals(Map.of("city", "Paris"), block.get("input"));
    }

    @Test
    void serverToolInputDoesNotLeakIntoTheCallBeforeIt() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_7\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\","
                        + "\"input\":{}}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                // A server tool's call: the shared model has no part for it, but its input still
                // streams as fragments — which must land in its own block, not in the call above.
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                        + "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\","
                        + "\"input\":{}}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                        + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"weather\\\"}\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // The client's call keeps its own input whole: the server tool's fragments were routed by
        // the bracket index, never appended to the part that happened to open last.
        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(2, aggregated.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, aggregated.getMessage().getParts().get(0));
        assertEquals("toolu_1", call.getCallId());
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());
        // The server tool's block arrives as itself, carrying the input its fragments spelled —
        // the answer a blocking walk gives it.
        RawContentBlock kept = assertInstanceOf(RawContentBlock.class,
                aggregated.getMessage().getParts().get(1));
        assertEquals("server_tool_use", kept.getMembers().get("type"));
        assertEquals(Map.of("query", "weather"), kept.getMembers().get("input"));
    }

    @Test
    void twoAdjacentTextBlocksStayTwoParts() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_8\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                        + "{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":\" world\"}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatStream stream = client.stream(requestWithModel());
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // Two blocks of the same kind are two parts: each delta joins the block its index names,
        // so nothing merges across the bracket between them.
        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(2, aggregated.getMessage().getParts().size());
        assertEquals("Hello",
                assertInstanceOf(TextPart.class, aggregated.getMessage().getParts().get(0)).getText());
        assertEquals(" world",
                assertInstanceOf(TextPart.class, aggregated.getMessage().getParts().get(1)).getText());
    }

    @Test
    void aToolCallWhoseInputNeverStreamedIsTheEmptyObjectOnTheWire() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_8\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"ping_now\","
                        + "\"input\":{}}}",
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}",
                "message_stop",
                "{\"type\":\"message_stop\"}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        while (events.hasNext()) {
            events.next();
        }

        // A call that takes no arguments streams none — and that is the empty object, the same
        // answer a blocking call gives it, not a fragment that never arrived.
        ToolCallPart call = assertInstanceOf(ToolCallPart.class,
                stream.aggregatedResponse().getMessage().getParts().get(0));
        assertEquals("ping_now", call.getName());
        assertEquals("{}", call.getArgumentsJson());

        stubCompletion();
        ChatRequest replay = requestWithModel();
        replay.addPendingMessage(stream.aggregatedResponse().getMessage());
        client.chat(replay);
        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        Map<String, Object> block = content.get(0);
        assertEquals(Map.of(), block.get("input"));
    }

    @Test
    void aBodyThatStopsWithoutMessageStopFailsAsTruncated() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        RecordedInputStream body = new RecordedInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_9\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}")
                .getBytes(UTF_8));
        stub.canned.setBody(body);

        ChatRequest request = requestWithModel();

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        assertEquals(AnthropicEventTypes.MESSAGE_START, events.next().getEventType());
        assertEquals(AnthropicEventTypes.CONTENT_BLOCK_START, events.next().getEventType());
        ChatStreamEvent third = events.next();
        assertEquals(AnthropicEventTypes.CONTENT_BLOCK_DELTA, third.getEventType());
        assertEquals("Hi", firstText(third));

        SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
        assertTrue(thrown.getMessage().contains("message_stop"), thrown.getMessage());
        assertTrue(body.closed, "a cut-short answer leaves nothing to read — the connection must go");
        // What arrived before the cut is still the caller's: the failure reports the answer, it
        // does not discard it.
        assertEquals("Hi", assertInstanceOf(TextPart.class,
                stream.aggregatedResponse().getMessage().getParts().get(0)).getText());
    }

    @Test
    void aStreamTheReaderRefusesStillReleasesTheResponse() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        RecordedInputStream body = new RecordedInputStream("data: {}\n\n".getBytes(UTF_8));
        stub.canned.setBody(body);

        ChatRequest request = requestWithModel();
        // A budget of zero is one the event stream refuses to read under, so the failure lands
        // between the response arriving and the stream taking ownership of it — where nothing else
        // holds the connection, and nobody but this path can release it.
        HttpOptions http = new HttpOptions();
        http.setMaxFrameBytes(0);
        request.getOptions().setHttpOptions(http);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> client.stream(request));
        assertTrue(thrown.getMessage().contains("maxFrameBytes"), thrown::toString);
        assertTrue(body.closed, "the response the stream never took must still be released");
    }

    @Test
    void anErrorEventFailsWhileIterating() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_a\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                        + "\"stop_reason\":null,\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}",
                "error",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                        + "\"message\":\"Overloaded\"}}")
                .getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        Iterator<ChatStreamEvent> events = client.stream(request).iterator();
        // The answer had already started, so the failure arrives with the frame that reports it.
        events.next();
        events.next();
        assertEquals("Hi", firstText(events.next()));
        SynapseException thrown = assertThrows(SynapseException.class, events::next);
        assertTrue(thrown.getMessage().contains("Overloaded"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("overloaded_error"), thrown.getMessage());
    }

    @Test
    void errorStatusBuildsMessageFromTheStructuredErrorBody() {
        stub.canned.setStatusCode(429);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                        + "\"message\":\"Rate limit reached\"},\"request_id\":\"req_1\"}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        String message = thrown.getMessage();
        assertTrue(message.contains("429"), message);
        assertTrue(message.contains("Rate limit reached"), message);
        assertTrue(message.contains("rate_limit_error"), message);
        assertEquals(429, assertInstanceOf(SynapseHttpException.class, thrown).getStatusCode());
    }

    @Test
    void nonJsonErrorBodyFallsBackToSnippetWithoutMaskingTheFailure() {
        stub.canned.setStatusCode(502);
        stub.canned.setBody(new ByteArrayInputStream("<html>Bad Gateway</html>".getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        String message = thrown.getMessage();
        assertTrue(message.contains("502"), message);
        assertTrue(message.contains("Bad Gateway"), message);
        assertEquals(502, assertInstanceOf(SynapseHttpException.class, thrown).getStatusCode());
    }

    @Test
    void aRefusedStreamFailsBeforeAnyEventIsHandedOut() {
        stub.canned.setStatusCode(429);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                        + "\"message\":\"Rate limit reached\"}}").getBytes(UTF_8)));

        ChatRequest request = requestWithModel();

        // A refusal is read exactly as it is for a blocking call, before the stream exists.
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.stream(request));
        assertTrue(thrown.getMessage().contains("429"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Rate limit reached"), thrown.getMessage());
        assertEquals(429, assertInstanceOf(SynapseHttpException.class, thrown).getStatusCode());
    }

    @Test
    void anAcceptedAnswerThatIsNotAnEventStreamFailsLoudly() {
        RecordedInputStream body = new RecordedInputStream(
                ("{\"type\":\"message\",\"content\":[]}").getBytes(UTF_8));
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("application/json")));
        stub.canned.setBody(body);

        ChatRequest request = requestWithModel();

        // A streamed request answered with something other than an event stream is a provider
        // contradicting itself; parsing the body as frames would only produce nonsense.
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.stream(request));
        assertTrue(thrown.getMessage().contains("200"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("text/event-stream"), thrown.getMessage());
        assertTrue(body.closed);
    }

    @Test
    void missingApiKeyAndMissingModelAreCallerBugs() {
        AnthropicConfig noKey = new AnthropicConfig();
        client.setConfig(noKey);
        ChatRequest request = requestWithModel();
        assertThrows(IllegalArgumentException.class, () -> client.chat(request));

        AnthropicConfig withKey = new AnthropicConfig();
        withKey.setApiKey("sk-ant-test");
        client.setConfig(withKey);
        request.getOptions().setModel(null);
        assertThrows(IllegalArgumentException.class, () -> client.chat(request));
    }

    @Test
    void userHeadersOverrideModuleHeaders() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().getHeaders().put("anthropic-version", "2024-01-01");
        request.getOptions().getHeaders().put("X-Custom", "yes");

        client.chat(request);

        // The caller's headers are applied last, so anything the module set can be overridden.
        assertEquals(List.of("2024-01-01"), stub.captured.getHeaders().get("anthropic-version"));
        assertEquals(List.of("yes"), stub.captured.getHeaders().get("X-Custom"));
    }

    @Test
    void extrasMergeOverTheModelledMembersOnTheirNode() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        request.getOptions().setTemperature(0.5);
        request.getOptions().getExtras().put("temperature", 0.9);
        request.getOptions().getExtras().put("metadata", Map.of("user_id", "u-1"));
        ChatMessage user = ChatMessage.user("Hello");
        user.setExtras(new ProviderExtras().put("name", "roger"));
        TextPart part = new TextPart("what is the weather?");
        part.setExtras(new ProviderExtras().put("cache_control", Map.of("type", "ephemeral")));
        user.getParts().set(0, part);
        request.addPendingMessage(user);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        // The bag wins where it sets a path, and lands as a member of the node it was added to.
        assertEquals(0.9, wire.get("temperature"));
        assertEquals(Map.of("user_id", "u-1"), wire.get("metadata"));
        assertFalse(wire.containsKey("extras"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals("roger", messages.get(0).get("name"));
        // A part carrying a field of its own forces the block form, and the field rides on it.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(Map.of("type", "text", "text", "what is the weather?",
                "cache_control", Map.of("type", "ephemeral")), content.get(0));
    }

    @Test
    void aSystemTextCarryingAFieldTakesTheBlockArray() {
        stubCompletion();

        ChatRequest request = requestWithModel();
        ChatMessage system = ChatMessage.system("You are helpful.");
        system.getParts().get(0).setExtras(new ProviderExtras().put("cache_control",
                Map.of("type", "ephemeral")));
        request.setSystemMessage(system);

        client.chat(request);

        // The string form cannot carry a cache breakpoint, so the framing takes the block array —
        // which is the only form where the field has somewhere to ride.
        assertEquals(List.of(Map.of("type", "text", "text", "You are helpful.",
                "cache_control", Map.of("type", "ephemeral"))), parseCaptured().get("system"));
    }

    @Test
    void closingTheStreamClosesTheResponse() {
        RecordedInputStream body = new RecordedInputStream(sse(
                "message_stop", "{\"type\":\"message_stop\"}").getBytes(UTF_8));
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(body);

        ChatRequest request = requestWithModel();

        ChatStream stream = client.stream(request);
        assertFalse(body.closed);
        // Cancelling an answer in flight is closing the connection behind it.
        stream.close();
        assertTrue(body.closed);
    }

    /** The finish reason a canned response with the given stop reason comes back with. */
    private String finishReasonOf(String stopReason) {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_x\"", "[]", stopReason,
                "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}").getBytes(UTF_8)));
        return client.chat(requestWithModel()).getFinishReason();
    }

    /** A canned message, as the pieces a test chooses to vary spell it. */
    private static String message(String id, String content, String stopReason, String usage) {
        return message(id, content, stopReason, usage, "");
    }

    private static String message(String id, String content, String stopReason, String usage,
            String moreMembers) {
        return "{\"id\":" + id + ",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-test\",\"content\":" + content + ","
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null," + usage
                + moreMembers + "}";
    }

    /** A canned message answering "ok", for the tests that only care about the request. */
    private void stubCompletion() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(message("\"msg_0\"",
                "[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn",
                "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}").getBytes(UTF_8)));
    }

    private Map<String, Object> parseCaptured() {
        try {
            // The client streams its body, so the captured body has to be written out to be read.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            stub.captured.getBody().writeTo(body);
            return codec.decode(body.toString(UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("captured wire body is not JSON", e);
        }
    }

    private static void assertNoNullValues(Map<String, Object> map) {
        map.forEach((key, value) -> assertNotNull(value, "wire field '" + key + "' was serialized as null"));
    }

    /** The content of the one message the wire carries, as the tests that send one message read it. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(Map<String, Object> wire) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        return (List<Map<String, Object>>) messages.get(0).get("content");
    }

    /** The source of one image block. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(Map<String, Object> block) {
        return (Map<String, Object>) block.get("source");
    }

    /**
     * An SSE body of the given frames, taken in pairs of event name and payload: one {@code event:}
     * line and one {@code data:} line each, followed by the blank line that dispatches it.
     */
    private static String sse(String... frames) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < frames.length; i += 2) {
            body.append("event: ").append(frames[i]).append("\ndata: ").append(frames[i + 1])
                    .append("\n\n");
        }
        return body.toString();
    }

    /** The text one event contributes, or {@code null} when it contributes none. */
    private static String textOf(ChatStreamEvent event) {
        if (event.getDelta() == null || event.getDelta().getParts().isEmpty()) {
            return null;
        }
        return ((TextPart) event.getDelta().getParts().get(0)).getText();
    }

    /** The first event's text, which the truncation and error tests read off their fragments. */
    private static String firstText(ChatStreamEvent event) {
        String text = textOf(event);
        assertNotNull(text, "the event carries no text");
        return text;
    }

    /**
     * A request as the minimal one this protocol can be asked with: a model and the token limit
     * the endpoint requires, which is all the request-writing tests here need.
     */
    private static ChatRequest requestWithModel() {
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("claude-test");
        request.getOptions().setMaxOutputTokens(64);
        return request;
    }

    /** A part type this module has no wire shape for: the model is open, the protocol is not. */
    static class UnmodelledPart extends ContentPart {
    }

}
