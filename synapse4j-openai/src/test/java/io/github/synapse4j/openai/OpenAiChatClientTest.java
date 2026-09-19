package io.github.synapse4j.openai;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.synapse4j.data.ChatFinishReason;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatResponseFormat;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.TextPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolDefinition;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import tools.jackson.databind.json.JsonMapper;

class OpenAiChatClientTest {

    /** Captures the outgoing request and replays a canned response. */
    static class StubHttpClient implements HttpClient {

        io.github.synapse4j.http.HttpRequest captured;
        HttpResponse canned = new HttpResponse();

        @Override
        public HttpResponse send(io.github.synapse4j.http.HttpRequest request) {
            this.captured = request;
            return canned;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCaptured() {
        try {
            return codec.decode(new String(stub.captured.getBody(), UTF_8), Map.class);
        } catch (RuntimeException e) {
            throw new AssertionError("captured wire body is not JSON", e);
        }
    }

    private static void assertNoNullValues(Map<String, Object> map) {
        map.forEach((key, value) -> assertNotNull(value, "wire field '" + key + "' was serialized as null"));
    }

    private static ChatMessage message(String role, String text) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.getParts().add(new TextPart(text));
        return message;
    }

}
