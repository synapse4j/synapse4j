package io.github.synapse4j.openai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.synapse4j.chat.AbstractChatClient;
import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.chat.DefaultChatStream;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonWriter;

/**
 * The OpenAI chat-completions client: speaks {@code POST /chat/completions} and answers in the
 * shared chat model.
 *
 * <p>
 * The request is written straight into the codec's {@code JsonWriter} and the response is walked
 * token by token through its {@code JsonReader}: neither direction builds the document as a
 * structure first. The wire shape is pinned by literal names — no codec-level setting, a naming
 * strategy among them, can rename a field, and a member whose value is not set is never emitted —
 * so whichever JSON library the application chose, the bytes on the wire are exactly the protocol's
 * spelling. The mapper-config rationale (方案一) still holds for the same reason it always did:
 * writing the protocol's own names takes that knob away. A field this module does not model is kept
 * in the extras of the node it came from rather than dropped. The status is read before the body is
 * touched, because the body is a stream and reaches the caller once: a non-2xx answer is buffered
 * for the error reader, a 2xx one is streamed into the adapter.
 *
 * <p>
 * A streamed answer is the same exchange with a different response body: the request asks for it
 * with {@code stream} and {@code stream_options} members of its own, the frames arrive as
 * {@code text/event-stream} and become events one for one, and the connection stays open until the
 * caller is done with it — closing the {@link ChatStream} is what cancels an answer still in
 * flight. A refusal is read exactly as it is for a blocking call, before the stream exists at all.
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
public class OpenAiChatClient extends AbstractChatClient {

    private final HttpClient http;
    private final JsonCodec codec;
    private final ChatCompletionsAdapter adapter;
    private final ChatCompletionsStreamAdapter streamAdapter;
    private final OpenAiErrorReader errorReader;

    private OpenAiConfig config = new OpenAiConfig();

    public OpenAiChatClient(HttpClient http, JsonCodec codec) {
        this.http = http;
        this.codec = codec;
        this.adapter = new ChatCompletionsAdapter(codec);
        this.streamAdapter = new ChatCompletionsStreamAdapter(codec);
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
    protected ChatResponse doChat(ChatRequest request) {
        requireCallable(request);

        io.github.synapse4j.http.HttpRequest httpRequest = httpRequest(request, out -> {
            // The body is written when the transport asks for it, and written again on every retry
            // or redirect: the document goes into whatever sink the implementation hands over, so it
            // never exists as bytes here.
            try (JsonWriter writer = codec.writer(out)) {
                adapter.writeTo(request, writer);
            }
        });

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

    @Override
    protected ChatStream doStream(ChatRequest request) {
        requireCallable(request);

        io.github.synapse4j.http.HttpRequest httpRequest = httpRequest(request, out -> {
            try (JsonWriter writer = codec.writer(out)) {
                adapter.writeStreamingTo(request, writer);
            }
        });

        io.github.synapse4j.http.HttpResponse httpResponse = http.send(httpRequest);
        int status = httpResponse.getStatusCode();
        if (status >= 200 && status < 300) {
            // The response decides whether it carries an event stream, and frames it with the budget
            // the call asked for: the transport merged the options, so nothing here merges again.
            SseEventStream events = httpResponse.sseEventStream();
            if (events == null) {
                try (io.github.synapse4j.http.HttpResponse notAnEventStream = httpResponse) {
                    throw new SynapseException("OpenAI answered " + status
                            + " to a streamed request, but not with a text/event-stream");
                } catch (IOException e) {
                    throw new SynapseException("OpenAI chat completion failed: response could not be read", e);
                }
            }
            // The response is deliberately left open: the stream owns it from here, and closing
            // the stream is what cancels an answer that is still in flight.
            DefaultChatStream stream = new DefaultChatStream(
                    streamAdapter.events(events), streamAdapter.aggregation(), httpResponse::close);
            // The headers arrive with the response, before any frame does, so they go onto the
            // answer now: aggregatedResponse() carries them the moment the stream exists, the
            // same way the answer of a blocking call does.
            ChatCompletionsAdapter.copyHeaders(stream.aggregatedResponse(), httpResponse.getHeaders());
            return stream;
        }
        throw refusal(httpResponse, status);
    }

    /**
     * The HTTP request both ways of asking share: one endpoint, one set of headers, the caller's
     * applied last. Only the body differs between them, so it is the one thing handed in.
     */
    private io.github.synapse4j.http.HttpRequest httpRequest(ChatRequest request,
            io.github.synapse4j.http.HttpBody body) {
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
        // The call's HTTP-level opinions travel with the request; what it does not set, the
        // transport fills in from its own options.
        httpRequest.setOptions(request.getOptions().getHttpOptions());
        httpRequest.setBody(body);
        return httpRequest;
    }

    /**
     * Reads the refusal off a response that never became a stream, and closes it — the stream was
     * not opened, so there is nothing to hand it to. The exception is the same one a refused
     * blocking call gets, since the provider's refusal is the same document either way.
     */
    private SynapseException refusal(io.github.synapse4j.http.HttpResponse httpResponse, int status) {
        try (io.github.synapse4j.http.HttpResponse refused = httpResponse) {
            return errorReader.read(status, readBody(refused));
        } catch (IOException e) {
            throw new SynapseException("OpenAI chat completion failed: response could not be read", e);
        }
    }

    /** A call the provider cannot even be asked: the caller's mistake, found before anything goes out. */
    private void requireCallable(ChatRequest request) {
        require(config.getApiKey() != null && !config.getApiKey().isBlank(), "apiKey is required");
        require(request.getOptions().getModel() != null && !request.getOptions().getModel().isBlank(),
                "options.model is required");
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
