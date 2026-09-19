package io.github.synapse4j.openai;

import java.io.IOException;
import java.util.List;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.openai.wire.ChatCompletionRequest;
import io.github.synapse4j.openai.wire.ChatCompletionResponse;
import io.github.synapse4j.schema.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAI chat-completions client: speaks {@code POST /chat/completions} and answers in the
 * shared chat model.
 *
 * <p>
 * Protocol bytes go through a private, fixed-configured Jackson ({@link OpenAiJson}) — never
 * through the application's {@code JsonCodec}. The codec the constructor accepts is stored for the
 * user-data concerns that will come (structured output binding, tool arguments); it is not used to
 * move protocol bytes, so which JSON library the application picked cannot change the wire shape.
 *
 * <p>
 * The client is stateless apart from the configuration and safe to share across threads.
 *
 * <p>
 * Header precedence is deliberate: the module sets {@code Content-Type}, {@code Authorization} and
 * the organization/project headers first, then applies the call's own headers last, so a caller
 * can override anything — the escape-hatch philosophy this library applies everywhere. The same
 * applies to validation: a misconfigured call (no config, no API key, no model) fails with
 * {@link IllegalArgumentException} before anything goes out, matching the restricted-header
 * precedent — the call never happened, so it is a caller bug, not a transport failure.
 */
public class OpenAiChatClient implements ChatClient {

    private final HttpClient http;

    // Unused on purpose: kept for the user-data concerns (structured output, tool arguments) that
    // will bind through the application's codec of choice. Protocol bytes never touch it.
    @SuppressWarnings("unused")
    private final JsonCodec codec;

    private final JsonMapper mapper;
    private final ChatCompletionsAdapter adapter;
    private final OpenAiErrorReader errorReader;

    private OpenAiConfig config = new OpenAiConfig();

    public OpenAiChatClient(HttpClient http, JsonCodec codec) {
        this.http = http;
        this.codec = codec;
        this.mapper = OpenAiJson.newMapper();
        this.adapter = new ChatCompletionsAdapter(mapper);
        this.errorReader = new OpenAiErrorReader(mapper);
    }

    /**
     * Replaces the family configuration this client sends with.
     *
     * @param config the new configuration; must not be {@code null}
     */
    public void setConfig(OpenAiConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        require(config.getApiKey() != null && !config.getApiKey().isBlank(), "apiKey is required");
        require(request.getOptions().getModel() != null && !request.getOptions().getModel().isBlank(),
                "options.model is required");

        ChatCompletionRequest wireRequest = adapter.toWire(request);
        io.github.synapse4j.http.HttpRequest httpRequest = new io.github.synapse4j.http.HttpRequest(
                config.getBaseUrl() + "/chat/completions");
        httpRequest.setMethod(io.github.synapse4j.http.HttpRequest.POST);
        httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
        httpRequest.getHeaders().put("Authorization", List.of("Bearer " + config.getApiKey()));
        if (config.getOrganization() != null) {
            httpRequest.getHeaders().put("OpenAI-Organization", List.of(config.getOrganization()));
        }
        if (config.getProject() != null) {
            httpRequest.getHeaders().put("OpenAI-Project", List.of(config.getProject()));
        }
        // Applied last, so a caller's header wins over any of the module's own.
        request.getOptions().getHeaders().forEach((name, value) -> httpRequest.getHeaders()
                .put(name, List.of(value)));
        httpRequest.setBody(encode(wireRequest));

        try (io.github.synapse4j.http.HttpResponse httpResponse = http.send(httpRequest)) {
            int status = httpResponse.getStatusCode();
            if (status >= 200 && status < 300) {
                return adapter.fromWire(decode(httpResponse), httpResponse.getHeaders());
            }
            throw errorReader.read(status, httpResponse.getBody());
        } catch (IOException e) {
            throw new SynapseException("OpenAI chat completion failed: response could not be read", e);
        }
    }

    private byte[] encode(ChatCompletionRequest wireRequest) {
        try {
            return mapper.writeValueAsBytes(wireRequest);
        } catch (JacksonException e) {
            throw new SynapseException("OpenAI request could not be encoded as JSON", e);
        }
    }

    private ChatCompletionResponse decode(io.github.synapse4j.http.HttpResponse httpResponse) {
        try {
            return mapper.readValue(httpResponse.getBody(), ChatCompletionResponse.class);
        } catch (JacksonException e) {
            throw new SynapseException("OpenAI response could not be decoded", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

}
