package io.github.synapse4j.anthropic;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.chat.AbstractHttpChatClient;
import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonWriter;
import lombok.NonNull;

/**
 * The Anthropic Messages client: speaks {@code POST /v1/messages} and answers in the shared chat
 * model, with blocking chat and streaming as equal citizens over one request model. The exchange
 * itself — one URL below a base URL, one set of headers, one validation before anything goes out,
 * the status read before the body, one refusal path — is the transport flow every provider shares,
 * in {@link AbstractHttpChatClient}; what this class settles is the Anthropic side of it.
 *
 * <p>
 * The answer is read token by token through the codec's {@link JsonReader} rather than built as a
 * structure first, and a field this module does not model is kept in the extras of the node it came
 * from rather than dropped.
 *
 * <p>
 * A streamed answer is the same exchange with a different response body: the request asks for it
 * with a {@code stream} member, the frames arrive as {@code text/event-stream} and become events
 * one for one, and the connection stays open until the caller is done with it — closing the
 * {@link ChatStream} is what cancels an answer still in flight. The conversation itself is never
 * continued here: the protocol keeps no state, so history and pending messages both go out on
 * every call, and {@link io.github.synapse4j.chat.ChatClient#continueWith} needs no override.
 *
 * <p>
 * Header precedence is deliberate: the module sets {@code Content-Type}, {@code x-api-key} — only
 * when a key is configured — and {@code anthropic-version} first, then applies the call's own
 * headers last, so a caller can override anything and can supply an {@code x-api-key} of its own
 * for an endpoint whose scheme differs. The same applies to validation: a call with no model fails
 * with {@link IllegalArgumentException} before anything goes out — the call never happened, so it
 * is a caller bug, not a transport failure.
 *
 * @see io.github.synapse4j.chat.ChatClient
 */
public class AnthropicChatClient extends AbstractHttpChatClient<AnthropicConfig> {

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the configuration to send with; must not be {@code null}
     */
    public AnthropicChatClient(@NonNull HttpClient http, @NonNull JsonCodec codec,
            @NonNull AnthropicConfig config) {
        super(http, codec, config);
    }

    @Override
    protected String url(AnthropicConfig config) {
        return withoutTrailingSlash(config.getBaseUrl()) + "/v1/messages";
    }

    @Override
    protected void headers(HttpRequest request, AnthropicConfig config) {
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            request.getHeaders().put("x-api-key", List.of(config.getApiKey()));
        }
        // The protocol version this call declares; the endpoint answers only to versions it knows.
        request.getHeaders().put("anthropic-version", List.of(config.getAnthropicVersion()));
    }

    @Override
    protected void write(ChatRequest request, JsonWriter writer, AnthropicConfig config, boolean streaming) {
        MessagesWriter document = new MessagesWriter(codec);
        if (streaming) {
            document.writeStreaming(request, writer);
        } else {
            document.write(request, writer);
        }
    }

    @Override
    protected ChatResponse read(JsonReader reader, AnthropicConfig config) {
        return new MessagesReader(codec).read(reader);
    }

    @Override
    protected ChatStream openStream(SseEventStream events, AutoCloseable closeAction, AnthropicConfig config) {
        return new MessagesStream(codec, events, eventPipeline(), closeAction);
    }

    @Override
    protected @Nullable String errorDetail(JsonReader reader) {
        Object error = reader.captureValue();
        return error instanceof Map<?, ?> members ? MessagesReader.errorDetail(members) : null;
    }

}
