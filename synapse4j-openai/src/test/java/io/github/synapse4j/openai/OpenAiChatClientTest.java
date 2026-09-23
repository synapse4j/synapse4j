package io.github.synapse4j.openai;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import io.github.synapse4j.data.MediaPart;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonView;
import io.github.synapse4j.util.InputStreamSupplier;
import tools.jackson.databind.json.JsonMapper;

class OpenAiChatClientTest {

    /** Captures the outgoing request and replays a canned response. */
    static class StubHttpClient implements HttpClient {

        io.github.synapse4j.http.HttpRequest captured;
        DefaultHttpResponse canned = new DefaultHttpResponse();
        io.github.synapse4j.http.HttpOptions options = io.github.synapse4j.http.HttpOptions.defaults();

        @Override
        public HttpResponse send(io.github.synapse4j.http.HttpRequest request) {
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
            canned.setOptions(io.github.synapse4j.http.HttpOptions.effective(request.getOptions(), options));
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
    private OpenAiChatClient client;

    @BeforeEach
    void setUp() {
        stub = new StubHttpClient();
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
        client = new OpenAiChatClient(stub, codec);
        OpenAiConfig config = new OpenAiConfig();
        config.setApiKey("sk-test");
        client.setConfig(config);
    }

    @Test
    void textRoundTripsAndWireRequestCarriesNoNulls() throws Exception {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hi there\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":3}}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getMessages().add(message(ChatRole.SYSTEM, "You are helpful."));
        request.getMessages().add(message(ChatRole.USER, "Hello"));
        request.getOptions().setModel("gpt-test");
        request.getOptions().setTemperature(0.5);
        request.getOptions().setMaxOutputTokens(64);
        request.getOptions().setTopP(0.9);

        ChatResponse response = client.chat(request);

        assertEquals("POST", stub.captured.getMethod());
        assertEquals("https://api.openai.com/v1/chat/completions", stub.captured.getUrl());
        assertEquals(List.of("application/json"), stub.captured.getHeaders().get("Content-Type"));
        assertEquals(List.of("Bearer sk-test"), stub.captured.getHeaders().get("Authorization"));

        Map<String, Object> wire = parseCaptured();
        assertEquals("gpt-test", wire.get("model"));
        assertEquals(0.5, wire.get("temperature"));
        assertEquals(64, wire.get("max_tokens"));
        assertEquals(0.9, wire.get("top_p"));
        assertNoNullValues(wire);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(2, messages.size());
        assertEquals(ChatRole.SYSTEM, messages.get(0).get("role"));
        // Text-only messages take the plain-string content form.
        assertEquals("You are helpful.", messages.get(0).get("content"));
        assertEquals(ChatRole.USER, messages.get(1).get("role"));
        assertEquals("Hello", messages.get(1).get("content"));

        assertEquals(1, response.getMessage().getParts().size());
        TextPart text = assertInstanceOf(TextPart.class, response.getMessage().getParts().get(0));
        assertEquals("Hi there", text.getText());
        assertEquals(ChatRole.ASSISTANT, response.getMessage().getRole());
        assertEquals(ChatFinishReason.STOP, response.getFinishReason());
        assertEquals("chatcmpl-1", response.getId());
        assertEquals("gpt-test", response.getModel());
        assertEquals(Integer.valueOf(11), response.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), response.getUsage().getOutputTokens());
        assertEquals(Integer.valueOf(3), response.getUsage().getCachedInputTokens());
    }

    @Test
    void aCustomizerPreparesTheRequestBeforeItIsWritten() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"id\":\"chatcmpl-3\",\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"}}]}").getBytes(UTF_8)));
        // The shape a preset takes: a shared field this endpoint spells differently moves into the
        // extras under the name it wants, and the shared field is cleared so it does not go out as
        // well.
        client.addChatRequestCustomizer(request -> {
            ChatOptions options = request.getOptions();
            options.getExtras().put("max_completion_tokens", options.getMaxOutputTokens());
            options.setMaxOutputTokens(null);
            return request;
        });

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().setMaxOutputTokens(64);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(64, wire.get("max_completion_tokens"));
        assertFalse(wire.containsKey("max_tokens"));
    }

    @Test
    void unknownFieldsAreKeptOnTheNodeTheyCameFrom() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"id\":\"chatcmpl-2\",\"model\":\"gpt-test\","
                + "\"created\":1700000000,\"system_fingerprint\":\"fp_1\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"logprobs\":null,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hi\",\"refusal\":null,"
                + "\"annotations\":[{\"type\":\"url_citation\"}]}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":4},"
                + "\"prompt_tokens_details\":{\"cached_tokens\":3,\"audio_tokens\":2}}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatResponse response = client.chat(request);

        // What the module still models is set as before.
        assertEquals("chatcmpl-2", response.getId());
        assertEquals("gpt-test", response.getModel());
        assertEquals(ChatFinishReason.STOP, response.getFinishReason());
        assertEquals(Integer.valueOf(11), response.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), response.getUsage().getOutputTokens());
        assertEquals(Integer.valueOf(3), response.getUsage().getCachedInputTokens());

        assertEquals(1700000000, response.getExtras().get("created"));
        assertEquals("fp_1", response.getExtras().get("system_fingerprint"));
        // A choice-level field has no bag of its own, so it keeps the path it came from.
        assertEquals(0, response.getExtras().get("choices", "0", "index"));
        assertTrue(response.getExtras().contains("choices", "0", "logprobs"));

        assertEquals(List.of(Map.of("type", "url_citation")),
                response.getMessage().getExtras().get("annotations"));
        // A field whose value is JSON null is captured too; there is nothing to assert beyond its
        // presence, since the value is null either way.
        assertTrue(response.getMessage().getExtras().contains("refusal"));

        assertEquals(18, response.getUsage().getExtras().get("total_tokens"));
        assertEquals(Map.of("reasoning_tokens", 4),
                response.getUsage().getExtras().get("completion_tokens_details"));
        assertEquals(2, response.getUsage().getExtras().get("prompt_tokens_details", "audio_tokens"));
    }

    @Test
    void anUnknownFieldOfAContentPartStaysOnThatPart() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\","
                + "\"annotations\":[{\"type\":\"url_citation\"}]}]}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatResponse response = client.chat(request);

        assertEquals(1, response.getMessage().getParts().size());
        TextPart part = assertInstanceOf(TextPart.class, response.getMessage().getParts().get(0));
        assertEquals("hi", part.getText());
        assertEquals(List.of(Map.of("type", "url_citation")), part.getExtras().get("annotations"));
    }

    @Test
    void theFirstChoiceIsReadAndAFurtherChoiceIsSkipped() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"id\":\"chatcmpl-3\",\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"first\"}},{\"index\":1,\"finish_reason\":\"length\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"second\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatResponse response = client.chat(request);

        assertEquals(1, response.getMessage().getParts().size());
        assertEquals("first", assertInstanceOf(TextPart.class, response.getMessage().getParts().get(0)).getText());
        assertEquals(ChatFinishReason.STOP, response.getFinishReason());
        assertFalse(response.getExtras().contains("choices", "1", "index"));
        // The fields after the skipped choice were still read, so the walk stayed in step.
        assertEquals(Integer.valueOf(1), response.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(2), response.getUsage().getOutputTokens());
    }

    @Test
    void responseHeadersAreCopiedOntoTheResponse() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("x-request-id", List.of("req_1"), "Retry-After", List.of("1", "2")));
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatResponse response = client.chat(request);

        assertEquals("req_1", response.getHeaders().get("x-request-id"));
        // One value per name in the shared model, so several values of a header are joined.
        assertEquals("1, 2", response.getHeaders().get("Retry-After"));
    }

    @Test
    void streamResponseHeadersAreCopiedOntoTheAggregatedResponse() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream"), "x-request-id",
                List.of("req_1"), "Retry-After", List.of("1", "2")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatStream stream = client.stream(request);
        // The headers are on the response before a single frame has been consumed, the way they
        // are on the answer of a blocking call.
        assertEquals("req_1", stream.aggregatedResponse().getHeaders().get("x-request-id"));
        assertEquals("1, 2", stream.aggregatedResponse().getHeaders().get("Retry-After"));

        List<ChatStreamEvent> events = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            events.add(event);
        }
        assertEquals(2, events.size());
    }

    @Test
    void anUnsupportedContentPartInTheResponseFailsLoudly() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"visible\"},"
                + "{\"type\":\"audio\",\"id\":\"a_1\"}]}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        assertTrue(thrown.getMessage().contains("audio"), thrown.getMessage());
    }

    @Test
    void toolDefinitionsGoOutAndToolCallsComeBack() throws Exception {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"choices\":[{\"index\":0,"
                + "\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":6}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getMessages().add(message(ChatRole.USER, "weather?"));
        request.getOptions().setModel("gpt-test");
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        request.getTools().add(tool);

        ChatResponse response = client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) wire.get("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
        assertEquals("get_weather", function.get("name"));
        assertEquals("Fetches weather", function.get("description"));
        // The schema string left the shared model as text and reaches the wire as a parsed object.
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertEquals("object", parameters.get("type"));
        assertEquals(Map.of("type", "string"),
                ((Map<?, ?>) parameters.get("properties")).get("city"));

        assertEquals(ChatFinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(1, response.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, response.getMessage().getParts().get(0));
        assertEquals("call_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());
        // "type" is not modelled on a tool call and stays on it rather than being dropped.
        assertEquals("function", call.getExtras().get("type"));
    }

    @Test
    void mixedPartsForceArrayContentForm() throws Exception {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"mixed\"}]}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage replay = new ChatMessage();
        replay.setRole(ChatRole.ASSISTANT);
        replay.getParts().add(new TextPart("Earlier text"));
        replay.getParts().add(new ToolCallPart("call_9", "get_weather", "{\"city\":\"Rome\"}"));
        request.getMessages().add(replay);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(List.of(Map.of("type", "text", "text", "Earlier text")), content);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        assertEquals("call_9", toolCalls.get(0).get("id"));
        assertEquals(Map.of("name", "get_weather", "arguments", "{\"city\":\"Rome\"}"),
                toolCalls.get(0).get("function"));
    }

    @Test
    void imageBytesGoOutInlinedAsADataUrl() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        byte[] bytes = { (byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff, 0x7f };
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new MediaPart("image/png", null, InputStreamSupplier.of(bytes), null));
        request.getMessages().add(message);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertNoNullValues(wire);
        List<Map<String, Object>> content = contentOf(wire);
        assertEquals(1, content.size());
        assertEquals("image_url", content.get(0).get("type"));
        String url = imageUrlOf(content.get(0));
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), url);
        // The payload is encoded as it is handed to the writer, so what follows the comma has to
        // decode back to the bytes that went in.
        assertArrayEquals(bytes, Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)));
    }

    @Test
    void anImageUriGoesOutAsTheUrlItself() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new MediaPart("image/png", "https://example.test/cat.png", null, null));
        request.getMessages().add(message);

        client.chat(request);

        List<Map<String, Object>> content = contentOf(parseCaptured());
        assertEquals(1, content.size());
        assertEquals("image_url", content.get(0).get("type"));
        assertEquals("https://example.test/cat.png", imageUrlOf(content.get(0)));
    }

    @Test
    void textAndImagePartsBecomeOrderedContentEntries() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        byte[] bytes = { 1, 2, 3 };
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new TextPart("what is this?"));
        message.getParts().add(new MediaPart("image/png", null, InputStreamSupplier.of(bytes), null));
        request.getMessages().add(message);

        client.chat(request);

        List<Map<String, Object>> content = contentOf(parseCaptured());
        assertEquals(2, content.size());
        assertEquals(Map.of("type", "text", "text", "what is this?"), content.get(0));
        assertEquals("image_url", content.get(1).get("type"));
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                imageUrlOf(content.get(1)));
    }

    @Test
    void mediaThatIsNotAnImageIsRejected() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new MediaPart("audio/wav", null, InputStreamSupplier.of(new byte[] { 1 }), null));
        request.getMessages().add(message);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        assertTrue(thrown.getMessage().contains("audio/wav"), thrown.getMessage());
    }

    @Test
    void mediaWithNeitherAUriNorASourceIsRejected() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new MediaPart("image/png", null, null, null));
        request.getMessages().add(message);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        assertTrue(thrown.getMessage().contains("neither uri nor source"), thrown.getMessage());
    }

    @Test
    void inliningAPayloadWithoutAMediaTypeIsRejected() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new MediaPart(null, null, InputStreamSupplier.of(new byte[] { 1 }), null));
        request.getMessages().add(message);

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        assertTrue(thrown.getMessage().contains("mediaType"), thrown.getMessage());
    }

    @Test
    void toolResultMessagesBecomeToolRoleEntries() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage result = new ChatMessage();
        result.setRole(ChatRole.TOOL);
        result.getParts().add(new ToolResultPart("call_9", "get_weather", List.of(new TextPart("sunny")), false));
        request.getMessages().add(result);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(1, messages.size());
        assertEquals("tool", messages.get(0).get("role"));
        assertEquals("call_9", messages.get(0).get("tool_call_id"));
        assertEquals("sunny", messages.get(0).get("content"));
    }

    @Test
    void jsonSchemaResponseFormatParsesSchemaIntoWire() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"{}\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatResponseFormat format = request.getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("answer");
        format.setSchema("{\"type\":\"object\"}");
        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) wire.get("response_format");
        assertEquals("json_schema", responseFormat.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
        assertEquals("answer", jsonSchema.get("name"));
        assertEquals(Map.of("type", "object"), jsonSchema.get("schema"));
    }

    @Test
    void errorStatusBuildsMessageFromStructuredErrorBody() {
        stub.canned.setStatusCode(429);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\","
                        + "\"code\":\"rpm\",\"param\":null}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        String message = thrown.getMessage();
        assertTrue(message.contains("429"), message);
        assertTrue(message.contains("Rate limit reached"), message);
        assertTrue(message.contains("rate_limit_exceeded"), message);
        assertTrue(message.contains("rpm"), message);
    }

    @Test
    void nonJsonErrorBodyFallsBackToSnippetWithoutMaskingTheFailure() {
        stub.canned.setStatusCode(502);
        stub.canned.setBody(new ByteArrayInputStream("<html>Bad Gateway</html>".getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        String message = thrown.getMessage();
        assertTrue(message.contains("502"), message);
        assertTrue(message.contains("Bad Gateway"), message);
    }

    @Test
    void missingApiKeyAndMissingModelAreCallerBugs() {
        OpenAiConfig noKey = new OpenAiConfig();
        client.setConfig(noKey);
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        assertThrows(IllegalArgumentException.class, () -> client.chat(request));

        OpenAiConfig withKey = new OpenAiConfig();
        withKey.setApiKey("sk-test");
        client.setConfig(withKey);
        request.getOptions().setModel(null);
        assertThrows(IllegalArgumentException.class, () -> client.chat(request));
    }

    @Test
    void optionsExtrasFlattenIntoTheWireTopLevel() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("service_tier", "priority");
        request.getOptions().getExtras().put("reasoning_effort", Map.of("effort", "low"));

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals("priority", wire.get("service_tier"));
        assertEquals(Map.of("effort", "low"), wire.get("reasoning_effort"));
        assertFalse(wire.containsKey("extras"));
        assertNoNullValues(wire);
    }

    @Test
    void extrasGoOutOnTheNodeTheyWereAddedTo() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("seed", 7);
        ChatMessage user = message(ChatRole.USER, "Hello");
        user.getExtras().put("name", "roger");
        user.getParts().get(0).getExtras().put("cache_control", Map.of("type", "ephemeral"));
        request.getMessages().add(user);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(7, wire.get("seed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals("roger", messages.get(0).get("name"));
        // A part that carries extras cannot be spelled inside a string, so the array form is taken.
        List<Map<String, Object>> content = contentOf(wire);
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertEquals("Hello", content.get(0).get("text"));
        assertEquals(Map.of("type", "ephemeral"), content.get(0).get("cache_control"));
    }

    @Test
    void anExtraReplacesAModelledMemberOfTheSameName() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().setTemperature(0.5);
        request.getOptions().getExtras().put("temperature", 0.9);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(0.9, wire.get("temperature"));
    }

    @Test
    void anExtraSetOverAWholeModelledObjectReplacesIt() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather", "{\"type\":\"object\"}");
        tool.getExtras().put("function", Map.of("name", "other"));
        request.getTools().add(tool);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) wire.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
        assertEquals(Map.of("name", "other"), function);
    }

    @Test
    void aToolDefinitionCarriesItsFunctionExtras() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ToolDefinition tool = new ToolDefinition("get_weather", "Fetches weather", "{\"type\":\"object\"}");
        tool.getExtras().put(List.of("function", "strict"), true);
        request.getTools().add(tool);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) wire.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
        // Both members have to sit in one function object: a second "function" member beside it
        // would leave only the last one standing, and "name" would be gone.
        assertEquals("get_weather", function.get("name"));
        assertEquals(Boolean.TRUE, function.get("strict"));
    }

    @Test
    void aReplayedToolCallCarriesItsFunctionExtras() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage replay = new ChatMessage();
        replay.setRole(ChatRole.ASSISTANT);
        ToolCallPart call = new ToolCallPart("call_1", "get_weather", "{}");
        call.getExtras().put(List.of("function", "provider_field"), "x");
        replay.getParts().add(call);
        request.getMessages().add(replay);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) messages.get(0).get("tool_calls");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
        assertEquals("get_weather", function.get("name"));
        assertEquals("{}", function.get("arguments"));
        assertEquals("x", function.get("provider_field"));
    }

    @Test
    void aMediaPartCarriesItsImageUrlExtras() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage user = new ChatMessage();
        user.setRole(ChatRole.USER);
        MediaPart image = new MediaPart("image/png", "https://example.com/a.png", null, null);
        image.getExtras().put(List.of("image_url", "detail"), "high");
        user.getParts().add(image);
        request.getMessages().add(user);

        client.chat(request);

        List<Map<String, Object>> content = contentOf(parseCaptured());
        assertEquals(1, content.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> imageUrl = (Map<String, Object>) content.get(0).get("image_url");
        assertEquals("https://example.com/a.png", imageUrl.get("url"));
        assertEquals("high", imageUrl.get("detail"));
    }

    @Test
    void aResponseFormatCarriesItsJsonSchemaExtras() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatResponseFormat format = request.getResponseFormat();
        format.setType(ChatResponseFormat.TYPE_JSON_SCHEMA);
        format.setName("answer");
        format.setSchema("{\"type\":\"object\"}");
        format.getExtras().put(List.of("json_schema", "strict"), true);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        Map<String, Object> responseFormat = (Map<String, Object>) wire.get("response_format");
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonSchema = (Map<String, Object>) responseFormat.get("json_schema");
        assertEquals("answer", jsonSchema.get("name"));
        assertEquals(Map.of("type", "object"), jsonSchema.get("schema"));
        assertEquals(Boolean.TRUE, jsonSchema.get("strict"));
    }

    @Test
    void aToolResultCarriesItsOwnExtras() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage result = new ChatMessage();
        result.setRole(ChatRole.TOOL);
        ToolResultPart part = new ToolResultPart("call_1", "get_weather", List.of(new TextPart("sunny")), false);
        part.getExtras().put("cache_control", Map.of("type", "ephemeral"));
        result.getParts().add(part);
        request.getMessages().add(result);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(1, messages.size());
        assertEquals("tool", messages.get(0).get("role"));
        assertEquals("call_1", messages.get(0).get("tool_call_id"));
        assertEquals("sunny", messages.get(0).get("content"));
        assertEquals(Map.of("type", "ephemeral"), messages.get(0).get("cache_control"));
    }

    @Test
    void aToolRoleMessageCarriesItsOwnExtrasOntoEveryEntryItBecomes() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage results = new ChatMessage();
        results.setRole(ChatRole.TOOL);
        results.getExtras().put("cache_control", Map.of("type", "ephemeral"));
        results.getParts().add(new ToolResultPart("call_1", "get_weather", List.of(new TextPart("sunny")), false));
        results.getParts().add(new ToolResultPart("call_2", "get_time", List.of(new TextPart("noon")), false));
        request.getMessages().add(results);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wire.get("messages");
        assertEquals(2, messages.size());
        assertEquals("call_1", messages.get(0).get("tool_call_id"));
        assertEquals("call_2", messages.get(1).get("tool_call_id"));
        assertEquals(Map.of("type", "ephemeral"), messages.get(0).get("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), messages.get(1).get("cache_control"));
    }

    @Test
    void anExtraNoneOfTheShapesCoversGoesOutAsTheCodecWritesIt() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("metadata", new Marker());

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(Map.of("source", "test"), wire.get("metadata"));
    }

    @Test
    void aSchemaInAnExtraGoesOutAsTheDocumentItDescribes() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        JsonSchema schema = new JsonSchema();
        schema.setType("object");
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("schema", schema);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(Map.of("type", "object"), wire.get("schema"));
    }

    @Test
    void aViewInAnExtraFailsLoudly() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("view", JsonView.of(Map.of("a", 1)));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> client.chat(request));

        assertTrue(thrown.getMessage().contains("JsonView"), thrown.getMessage());
    }

    @Test
    void anExtrasBagInsideAnExtraGoesOutAsTheObjectItDescribes() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ProviderExtras nested = new ProviderExtras().put(List.of("annotations", "title"), "x");
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getExtras().put("metadata", nested);

        client.chat(request);

        Map<String, Object> wire = parseCaptured();
        assertEquals(Map.of("annotations", Map.of("title", "x")), wire.get("metadata"));
    }

    @Test
    void anEmptyTextPartWithExtrasStillGoesOut() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage user = new ChatMessage();
        user.setRole(ChatRole.USER);
        TextPart part = new TextPart("");
        part.getExtras().put("cache_control", Map.of("type", "ephemeral"));
        user.getParts().add(part);
        request.getMessages().add(user);

        client.chat(request);

        List<Map<String, Object>> content = contentOf(parseCaptured());
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertEquals(Map.of("type", "ephemeral"), content.get(0).get("cache_control"));
        // There is nothing to say, so no text member goes out: an empty string would be a value the
        // caller never set.
        assertFalse(content.get(0).containsKey("text"));
    }

    @Test
    void aToolResultContentPartWithExtrasFailsLoudly() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(okBody());

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage result = new ChatMessage();
        result.setRole(ChatRole.TOOL);
        TextPart text = new TextPart("sunny");
        text.getExtras().put("cache_control", Map.of("type", "ephemeral"));
        result.getParts().add(new ToolResultPart("call_1", "get_weather", List.of(text), false));
        request.getMessages().add(result);

        // The tool message's content is a plain string, so a part carrying extras would have them
        // dropped on the way out without a word.
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.chat(request));
        assertTrue(thrown.getMessage().contains("extras"), thrown.getMessage());
    }

    @Test
    void unsupportedPartsFailLoudly() {
        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        ChatMessage message = new ChatMessage();
        message.setRole(ChatRole.USER);
        message.getParts().add(new io.github.synapse4j.data.ReasoningPart());
        request.getMessages().add(message);

        assertThrows(SynapseException.class, () -> client.chat(request));
    }

    @Test
    void userHeadersOverrideModuleHeaders() {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getOptions().getHeaders().put("Authorization", "Bearer sk-override");
        request.getOptions().getHeaders().put("X-Custom", "yes");

        client.chat(request);

        assertEquals(List.of("Bearer sk-override"), stub.captured.getHeaders().get("Authorization"));
        assertEquals(List.of("yes"), stub.captured.getHeaders().get("X-Custom"));
    }

    @Test
    void theRequestBodyIsStreamedRatherThanMaterialized() throws Exception {
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getMessages().add(message(ChatRole.USER, "Hello"));

        client.chat(request);

        // A streamed body produces its bytes on demand, so there is no buffer holding them.
        assertNull(stub.captured.getBody().buffer());
        assertEquals("gpt-test", parseCaptured().get("model"));

        // Producing them again, which is what a retry or a redirect does, gives the same document.
        ByteArrayOutputStream retry = new ByteArrayOutputStream();
        stub.captured.getBody().writeTo(retry);
        assertEquals(parseCaptured(), codec.decode(retry.toString(UTF_8), Map.class));
    }

    @Test
    void aTextStreamBecomesOneEventPerFrameAndAggregatesToTheSameAnswer() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},"
                        + "\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\" there\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}",
                "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        request.getMessages().add(message(ChatRole.USER, "Hello"));

        ChatStream stream = client.stream(request);
        List<ChatStreamEvent> events = new ArrayList<>();
        for (ChatStreamEvent event : stream) {
            events.add(event);
        }

        // One event per frame, the frame that ends the answer included.
        assertEquals(5, events.size());
        assertEquals(OpenAiEventTypes.CHUNK, events.get(0).getEventType());
        assertEquals(ChatRole.ASSISTANT, events.get(0).getDelta().getRole());
        // The empty fragment that opens a turn adds nothing to it.
        assertTrue(events.get(0).getDelta().getParts().isEmpty());
        assertEquals("Hi", textOf(events.get(1)));
        assertEquals(" there", textOf(events.get(2)));
        assertEquals(ChatFinishReason.STOP, events.get(2).getFinishReason());
        assertEquals(Integer.valueOf(11), events.get(3).getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), events.get(3).getUsage().getOutputTokens());
        assertEquals(OpenAiEventTypes.DONE, events.get(4).getEventType());
        assertNull(events.get(4).getDelta());

        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(ChatRole.ASSISTANT, aggregated.getMessage().getRole());
        assertEquals(1, aggregated.getMessage().getParts().size());
        assertEquals("Hi there",
                assertInstanceOf(TextPart.class, aggregated.getMessage().getParts().get(0)).getText());
        assertEquals(ChatFinishReason.STOP, aggregated.getFinishReason());
        assertEquals("chatcmpl-1", aggregated.getId());
        assertEquals("gpt-test", aggregated.getModel());
        assertEquals(Integer.valueOf(11), aggregated.getUsage().getInputTokens());
        assertEquals(Integer.valueOf(7), aggregated.getUsage().getOutputTokens());

        // The same answer asked for in one piece has to come back the same way.
        stub.canned = new DefaultHttpResponse();
        stub.canned.setStatusCode(200);
        stub.canned.setBody(new ByteArrayInputStream(("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hi there\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}").getBytes(UTF_8)));
        ChatResponse blocking = client.chat(request);

        assertEquals(blocking.getMessage().getRole(), aggregated.getMessage().getRole());
        assertEquals(blocking.getMessage().getParts(), aggregated.getMessage().getParts());
        assertEquals(blocking.getFinishReason(), aggregated.getFinishReason());
        assertEquals(blocking.getId(), aggregated.getId());
        assertEquals(blocking.getModel(), aggregated.getModel());
        assertEquals(blocking.getUsage().getInputTokens(), aggregated.getUsage().getInputTokens());
        assertEquals(blocking.getUsage().getOutputTokens(), aggregated.getUsage().getOutputTokens());
    }

    @Test
    void toolCallFragmentsMergeIntoOneCall() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"{\\\"city\\\":\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"\\\"Paris\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        int frames = 0;
        while (events.hasNext()) {
            events.next();
            frames++;
        }
        assertEquals(4, frames);

        ChatResponse aggregated = stream.aggregatedResponse();
        // The arguments were split across three chunks and the call named only once.
        assertEquals(1, aggregated.getMessage().getParts().size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, aggregated.getMessage().getParts().get(0));
        assertEquals("call_1", call.getCallId());
        assertEquals("get_weather", call.getName());
        assertEquals("{\"city\":\"Paris\"}", call.getArgumentsJson());
        // A field the module does not model travelled with the fragments it came in on.
        assertEquals("function", call.getExtras().get("type"));
        assertEquals(ChatFinishReason.TOOL_CALLS, aggregated.getFinishReason());
    }

    @Test
    void parallelToolCallFragmentsMergeIntoTheirOwnCalls() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                // Both calls are named in the chunk that opens them, and their arguments then stream
                // one after the other, each fragment carrying only the position of its call.
                "{\"id\":\"chatcmpl-4\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":["
                        + "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}},"
                        + "{\"index\":1,\"id\":\"call_2\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"get_time\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-4\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"{\\\"city\\\":\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-4\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":"
                        + "{\"arguments\":\"{\\\"zone\\\":\"}}]},\"finish_reason\":null}]}",
                "{\"id\":\"chatcmpl-4\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"\\\"Paris\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatStream stream = client.stream(request);
        Iterator<ChatStreamEvent> events = stream.iterator();
        int frames = 0;
        while (events.hasNext()) {
            events.next();
            frames++;
        }
        assertEquals(5, frames);

        ChatResponse aggregated = stream.aggregatedResponse();
        assertEquals(2, aggregated.getMessage().getParts().size());
        ToolCallPart first = assertInstanceOf(ToolCallPart.class, aggregated.getMessage().getParts().get(0));
        ToolCallPart second = assertInstanceOf(ToolCallPart.class, aggregated.getMessage().getParts().get(1));
        assertEquals("call_1", first.getCallId());
        assertEquals("get_weather", first.getName());
        assertEquals("{\"city\":\"Paris\"}", first.getArgumentsJson());
        assertEquals("call_2", second.getCallId());
        assertEquals("get_time", second.getName());
        assertEquals("{\"zone\":", second.getArgumentsJson());
    }

    @Test
    void anErrorFrameFailsWhileIterating() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(
                "{\"id\":\"chatcmpl-3\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi\"},"
                        + "\"finish_reason\":null}]}",
                "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\","
                        + "\"code\":\"rpm\"}}")
                .getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        Iterator<ChatStreamEvent> events = client.stream(request).iterator();
        // The answer had already started, so the failure arrives with the frame that reports it.
        assertEquals("Hi", textOf(events.next()));
        SynapseException thrown = assertThrows(SynapseException.class, events::next);
        assertTrue(thrown.getMessage().contains("Rate limit reached"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("rate_limit_exceeded"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("rpm"), thrown.getMessage());
    }

    @Test
    void aRefusedStreamFailsBeforeAnyEventIsHandedOut() {
        stub.canned.setStatusCode(429);
        stub.canned.setBody(new ByteArrayInputStream(
                ("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\","
                        + "\"code\":\"rpm\",\"param\":null}}").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        SynapseException thrown = assertThrows(SynapseException.class, () -> client.stream(request));
        assertTrue(thrown.getMessage().contains("429"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Rate limit reached"), thrown.getMessage());
    }

    @Test
    void anAcceptedAnswerThatIsNotAnEventStreamFailsLoudly() {
        RecordedInputStream body = new RecordedInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"an answer, not a stream\"}}]}").getBytes(UTF_8));
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("application/json")));
        stub.canned.setBody(body);

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        // A streamed request answered with something other than an event stream is a provider
        // contradicting itself; parsing the body as frames would only produce nonsense.
        SynapseException thrown = assertThrows(SynapseException.class, () -> client.stream(request));

        assertTrue(thrown.getMessage().contains("200"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("text/event-stream"), thrown.getMessage());
        // Nothing is left holding the connection.
        assertTrue(body.closed);
    }

    @Test
    void closingTheStreamClosesTheResponse() {
        RecordedInputStream body = new RecordedInputStream(
                sse("[DONE]").getBytes(UTF_8));
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(body);

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        ChatStream stream = client.stream(request);
        assertFalse(body.closed);
        // Cancelling an answer in flight is closing the connection behind it.
        stream.close();
        assertTrue(body.closed);
    }

    @Test
    void theStreamingRequestAsksForTheUsageFrame() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse("[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        client.stream(request).close();

        Map<String, Object> wire = parseCaptured();
        assertEquals(Boolean.TRUE, wire.get("stream"));
        // A streamed answer reports its usage in a frame of its own, and only when asked to.
        assertEquals(Map.of("include_usage", true), wire.get("stream_options"));
    }

    @Test
    void aRequestLevelFrameBudgetAppliesToTheStream() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(bigChunk(), "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");
        io.github.synapse4j.http.HttpOptions http = new io.github.synapse4j.http.HttpOptions();
        http.setMaxFrameBytes(64);
        request.getOptions().setHttpOptions(http);

        Iterator<ChatStreamEvent> events = client.stream(request).iterator();

        SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
        assertTrue(thrown.getMessage().contains("64"), thrown.getMessage());
    }

    @Test
    void theStandardFrameBudgetAppliesWhenNothingIsSet() {
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse("x".repeat(300 * 1024), "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        Iterator<ChatStreamEvent> events = client.stream(request).iterator();

        SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
        assertTrue(thrown.getMessage().contains("262144"), thrown.getMessage());
    }

    @Test
    void theClientOwnFrameBudgetAppliesWhenTheRequestSetsNothing() {
        stub.options.setMaxFrameBytes(64);
        stub.canned.setStatusCode(200);
        stub.canned.getHeaders().putAll(Map.of("Content-Type", List.of("text/event-stream")));
        stub.canned.setBody(new ByteArrayInputStream(sse(bigChunk(), "[DONE]").getBytes(UTF_8)));

        ChatRequest request = new ChatRequest();
        request.getOptions().setModel("gpt-test");

        Iterator<ChatStreamEvent> events = client.stream(request).iterator();

        SynapseException thrown = assertThrows(SynapseException.class, events::hasNext);
        assertTrue(thrown.getMessage().contains("64"), thrown.getMessage());
    }

    /** One chunk frame with a content long enough to blow any small frame budget. */
    private static String bigChunk() {
        return "{\"id\":\"chatcmpl-9\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
                + "x".repeat(100) + "\"},\"finish_reason\":null}]}";
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

    /** The URL of one {@code image_url} content entry. */
    @SuppressWarnings("unchecked")
    private static String imageUrlOf(Map<String, Object> entry) {
        Map<String, Object> imageUrl = (Map<String, Object>) entry.get("image_url");
        return (String) imageUrl.get("url");
    }

    /**
     * An SSE body of the given frames: one {@code data:} line each, followed by the blank line that
     * dispatches it.
     */
    private static String sse(String... frames) {
        StringBuilder body = new StringBuilder();
        for (String frame : frames) {
            body.append("data: ").append(frame).append("\n\n");
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

    private static ChatMessage message(String role, String text) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.getParts().add(new TextPart(text));
        return message;
    }

    /** A value only the JSON library behind the codec can turn into JSON. */
    static class Marker {

        public String source = "test";

    }

    /** A canned 200 body the request-writing tests answer with, since none of them read it. */
    private static ByteArrayInputStream okBody() {
        return new ByteArrayInputStream(
                ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"}}]}").getBytes(UTF_8));
    }

}
