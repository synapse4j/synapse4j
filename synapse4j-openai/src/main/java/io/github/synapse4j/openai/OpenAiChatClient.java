package io.github.synapse4j.openai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;

/**
 * The OpenAI chat-completions client: speaks {@code POST /chat/completions} and answers in the
 * shared chat model.
 *
 * <p>
 * Protocol documents are plain {@code Map}/{@code List} structures bound by the application's own
 * {@code JsonCodec}. The wire shape is pinned by literal keys: a map key cannot be renamed by a
 * codec's naming strategy, and an absent entry is never emitted — so whichever JSON library the
 * application chose, the bytes on the wire are exactly the protocol's spelling. The mapper-config
 * rationale (方案一) still holds for the same reason it always did: with the application's codec
 * doing the binding, any codec-level setting (a naming strategy among them) would have changed the
 * wire shape; literal keys take that knob away. Responses are walked token by token through the
 * codec's {@code JsonReader} instead of being decoded into a tree first, and a field this module
 * does not model is kept in the extras of the node it came from rather than dropped. The status is
 * read before the body is touched, because the body is a stream and reaches the caller once: a
 * non-2xx answer is buffered for the error reader, a 2xx one is streamed into the adapter.
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
    private final JsonCodec codec;
    private final ChatCompletionsAdapter adapter;
    private final OpenAiErrorReader errorReader;

    private OpenAiConfig config = new OpenAiConfig();

    public OpenAiChatClient(HttpClient http, JsonCodec codec) {
        this.http = http;
        this.codec = codec;
        this.adapter = new ChatCompletionsAdapter(codec);
        this.errorReader = new OpenAiErrorReader(codec);
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

        Map<String, Object> wireRequest = adapter.toWire(request);
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
        byte[] wireBody = codec.encode(wireRequest).getBytes(StandardCharsets.UTF_8);
        httpRequest.setBody(io.github.synapse4j.http.HttpBody.of(wireBody));

        try (io.github.synapse4j.http.HttpResponse httpResponse = http.send(httpRequest)) {
            // The body is a stream and can be read once, so the status decides how it is read
            // before anything is consumed.
            int status = httpResponse.getStatusCode();
            if (status >= 200 && status < 300) {
                try (JsonReader reader = codec.reader(httpResponse.getBody())) {
                    return adapter.fromWire(reader, httpResponse.getHeaders());
                }
            }
            throw errorReader.read(status, readBody(httpResponse));
        } catch (IOException e) {
            throw new SynapseException("OpenAI chat completion failed: response could not be read", e);
        }
    }

    private String readBody(io.github.synapse4j.http.HttpResponse httpResponse) throws IOException {
        return new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

}
