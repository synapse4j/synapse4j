package io.github.synapse4j.openai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.chat.AbstractChatClient;
import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseHttpException;
import io.github.synapse4j.http.HttpBody;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonWriter;
import lombok.NonNull;

/**
 * The transport flow behind this module's two endpoints: chat completions and Responses are two
 * protocols of one family, and everything but three things is the same request going out and an
 * answer coming back — one endpoint below a base URL, one set of headers, one validation before
 * anything goes out, one way of reading the status before the body, one refusal path, one way of
 * streaming what an accepted answer carries. What differs is the endpoint, the document, and how
 * an answer comes back, and those three are the hooks below; a fix to the flow lands on both
 * endpoints at once because there is only one flow to fix.
 *
 * <p>
 * The request is written straight into the codec's {@code JsonWriter} and the response is walked
 * token by token through its {@code JsonReader}: neither direction builds the document as a
 * structure first, and a member whose value is not set is never emitted. A field this module does
 * not model is kept in the extras of the node it came from rather than dropped. The status is read
 * before the body is touched, because the body is a stream and reaches the caller once: a non-2xx
 * answer is buffered for the detail it carries, a 2xx one is streamed into the reader.
 *
 * <p>
 * A streamed answer is the same exchange with a different response body: the request asks for it
 * with a member of its own, the frames arrive as {@code text/event-stream} and become events one
 * for one, and the connection stays open until the caller is done with it — closing the
 * {@link ChatStream} is what cancels an answer still in flight. A refusal is read exactly as it is
 * for a blocking call, before the stream exists at all.
 *
 * <p>
 * The client is stateless apart from the configuration and safe to share across threads.
 *
 * <p>
 * Header precedence is deliberate: the module sets {@code Content-Type}, {@code Authorization} —
 * only when a key is configured — and the organization/project headers first, then applies the
 * call's own headers last, so a caller can override anything and can supply an {@code Authorization}
 * of its own for an endpoint whose scheme differs. The same applies to validation: a call with no
 * model fails with {@link IllegalArgumentException} before anything goes out, matching the
 * restricted-header precedent — the call never happened, so it is a caller bug, not a transport
 * failure.
 */
public abstract class AbstractOpenAiChatClient extends AbstractChatClient {

    /** Raw-body snippet kept in the message when the error body is not parseable JSON. */
    private static final int SNIPPET_LIMIT = 500;

    /**
     * The most bytes a refusal may spend reading its body: enough for any conventional error
     * document, and enough for a snippet when it is not one — a gateway that answers a failure
     * with an endless body is not paid for past this.
     */
    private static final int ERROR_BODY_LIMIT = 64 * 1024;

    @NonNull
    private final HttpClient http;

    /**
     * The application's codec. It stays reachable to the subclasses because the hooks that build a
     * writer, a reader or a stream hand it on: one codec serves every document this exchange
     * touches, whichever protocol spells it.
     */
    @NonNull
    protected final JsonCodec codec;

    /**
     * The family configuration in effect, replaceable while the client is in use. The reference
     * follows the same pattern as the tool containers in {@link AbstractChatClient}: a rare writer
     * publishes a whole new instance through it in one step, and every exchange reads it once into
     * a local — two reads could straddle two versions, and a request must not go out partly under
     * one configuration and partly under another.
     */
    private final AtomicReference<OpenAiConfig> config;

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the family configuration to send with; must not be {@code null}
     */
    protected AbstractOpenAiChatClient(@NonNull HttpClient http, @NonNull JsonCodec codec,
            @NonNull OpenAiConfig config) {
        this.http = http;
        this.codec = codec;
        this.config = new AtomicReference<>(config);
    }

    /**
     * Replaces the family configuration this client sends with. In flight exchanges keep the
     * instance they started with; the next one sees the new value.
     *
     * @param config the new configuration; must not be {@code null}
     */
    public void setConfig(@NonNull OpenAiConfig config) {
        this.config.set(config);
    }

    /**
     * The family configuration in effect. A subclass that has to consult it outside an exchange —
     * the Responses adapter reads it while folding an answer in — reads it here once, the way
     * {@link #doChat} and {@link #doStream} snapshot it for one exchange: two reads could straddle
     * a {@link #setConfig} and answer under two configurations.
     *
     * @return the configuration in effect; never {@code null}
     */
    protected final OpenAiConfig config() {
        return config.get();
    }

    @Override
    protected ChatResponse doChat(ChatRequest request) {
        // One snapshot for the whole exchange: a setConfig landing mid-call must not send this
        // request partly under the old configuration and partly under the new.
        OpenAiConfig config = this.config.get();
        requireCallable(request);

        HttpRequest httpRequest = httpRequest(endpoint(), config, request, out -> {
            // The body is written when the transport asks for it, and written again on every retry
            // or redirect: the document goes into whatever sink the implementation hands over, so it
            // never exists as bytes here.
            try (JsonWriter writer = codec.writer(out)) {
                write(request, writer, config, false);
            }
        });

        try (HttpResponse httpResponse = http.send(httpRequest)) {
            // The body is a stream and can be read once, so the status decides how it is read
            // before anything is consumed.
            int status = httpResponse.getStatusCode();
            if (status >= 200 && status < 300) {
                try (JsonReader reader = codec.reader(httpResponse.getBody())) {
                    ChatResponse response = read(reader, config);
                    copyHeaders(response, httpResponse.getHeaders());
                    return response;
                }
            }
            throw failure(status, readBody(httpResponse));
        } catch (IOException e) {
            throw new SynapseException("OpenAI " + protocol() + " failed: response could not be read", e);
        }
    }

    @Override
    protected ChatStream doStream(ChatRequest request) {
        // The same once-per-exchange snapshot the blocking path takes.
        OpenAiConfig config = this.config.get();
        requireCallable(request);

        HttpRequest httpRequest = httpRequest(endpoint(), config, request, out -> {
            try (JsonWriter writer = codec.writer(out)) {
                write(request, writer, config, true);
            }
        });

        HttpResponse httpResponse = http.send(httpRequest);
        int status = httpResponse.getStatusCode();
        if (status >= 200 && status < 300) {
            try {
                // The response decides whether it carries an event stream, and frames it with the budget
                // the call asked for: the transport merged the options, so nothing here merges again.
                SseEventStream events = httpResponse.sseEventStream();
                if (events == null) {
                    throw new SynapseException("OpenAI answered " + status
                            + " to a streamed request, but not with a text/event-stream");
                }
                // The response is deliberately left open on success: the stream owns it from here,
                // and closing the stream is what cancels an answer that is still in flight.
                ChatStream stream = openStream(events, httpResponse::close, config);
                // The headers arrive with the response, before any frame does, so they go onto the
                // answer now: aggregatedResponse() carries them the moment the stream exists, the
                // same way the answer of a blocking call does.
                copyHeaders(stream.aggregatedResponse(), httpResponse.getHeaders());
                return stream;
            } catch (RuntimeException failure) {
                // Between the response arriving and the stream taking ownership of it, nothing else
                // holds the connection: whatever broke here would leave it held by a response no one
                // has, so release it before the failure leaves.
                try (HttpResponse closing = httpResponse) {
                    // The close is what this path owes; the comment is what it is owed for.
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
        throw refusal(httpResponse, status);
    }

    /**
     * The path this protocol's endpoint sits at, below the configured base URL.
     *
     * @return the endpoint path; never {@code null}
     */
    protected abstract String endpoint();

    /**
     * The protocol's name as this family spells it in its own failure messages — the part that
     * says which of the two endpoints a caller was talking to when an answer could not be read.
     * The messages are assembled from it so the wording stays exactly where each protocol always
     * put it without the flow carrying two copies of a sentence.
     *
     * @return the name as it sits between "OpenAI " and " failed"; never {@code null}
     */
    protected abstract String protocol();

    /**
     * Writes the request document — the blocking or the streaming spelling, as the flag says —
     * into the writer the transport handed over. The document is the protocol's own: its members,
     * its names, and which writer builds it are what differs between the endpoints; everything
     * around it is not.
     *
     * @param request   the request to translate
     * @param writer    the writer to write into; owned by this call, and closed with it
     * @param config    the endpoint's conventions for this exchange
     * @param streaming whether the document is the one a streamed answer goes out with
     */
    protected abstract void write(ChatRequest request, JsonWriter writer, OpenAiConfig config, boolean streaming);

    /**
     * Walks a successful response body into the shared answer, reading the protocol's document
     * through the reader the transport's stream was wrapped in.
     *
     * @param reader the reader, before its first token; owned by this call, and closed with it
     * @param config the endpoint's conventions for this exchange
     * @return the answer; never {@code null}
     */
    protected abstract ChatResponse read(JsonReader reader, OpenAiConfig config);

    /**
     * Builds the streaming answer over an accepted response: the frames, the fold, and what
     * releasing the stream releases are the protocol's to wire, which is why they are not decided
     * here. The codec and the event pipeline are reachable where this runs, so the exchange hands
     * the stream nothing it cannot already reach itself.
     *
     * @param events      the response's event frames, in arrival order
     * @param closeAction what releasing the stream does — closing the HTTP response behind it
     * @param config      the endpoint's conventions for this exchange
     * @return the streaming answer; never {@code null}
     */
    protected abstract ChatStream openStream(SseEventStream events, AutoCloseable closeAction, OpenAiConfig config);

    /**
     * Copies the HTTP response headers onto the shared response. The shared model holds one value
     * per name, so several values of a header are joined the way a blocking call joins them — the
     * transport metadata of an answer must not depend on which way it was asked for.
     *
     * @param response    the response to carry the headers
     * @param httpHeaders the response headers, as the transport reports them
     */
    private static void copyHeaders(ChatResponse response, Map<String, List<String>> httpHeaders) {
        httpHeaders.forEach((name, values) -> response.getHeaders().put(name, String.join(", ", values)));
    }

    /**
     * The base URL as an endpoint path is appended to it: a trailing slash is dropped, so a
     * configured {@code https://host/v1/} does not put a doubled slash in the path.
     *
     * @param baseUrl the configured base URL; never {@code null}
     * @return the base URL without a trailing slash
     */
    private static String withoutTrailingSlash(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    /**
     * The HTTP request both ways of asking share: one endpoint, one set of headers, the caller's
     * applied last. Only the body differs between them, so it is the one thing handed in beside
     * the path, which the protocol names.
     */
    private HttpRequest httpRequest(String endpoint, OpenAiConfig config,
            ChatRequest request, HttpBody body) {
        HttpRequest httpRequest = new HttpRequest(
                withoutTrailingSlash(config.getBaseUrl()) + endpoint);
        httpRequest.setMethod(HttpRequest.POST);
        httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            httpRequest.getHeaders().put("Authorization", List.of("Bearer " + config.getApiKey()));
        }
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
    private SynapseException refusal(HttpResponse httpResponse, int status) {
        try (HttpResponse refused = httpResponse) {
            return failure(status, readBody(refused));
        } catch (IOException e) {
            throw new SynapseException("OpenAI " + protocol() + " failed: response could not be read", e);
        }
    }

    /** A call the provider cannot even be asked: the caller's mistake, found before anything goes out. */
    private void requireCallable(ChatRequest request) {
        require(request.getOptions().getModel() != null && !request.getOptions().getModel().isBlank(),
                "options.model is required");
    }

    private byte[] readBody(HttpResponse httpResponse) throws IOException {
        // Bounded: a refusal needs enough of the body to parse the error document or show a
        // snippet of it, and no hostile or broken gateway gets paid for more. What lies past the
        // limit is never read — the caller's try-with-resources closes the response, connection
        // and all, which is what stops it.
        return httpResponse.getBody().readNBytes(ERROR_BODY_LIMIT);
    }

    /**
     * The exception for a call the provider refused: the status always, the provider's own detail
     * when the body parses as the conventional error document, a snippet of the raw body when it
     * does not. A walk that fails is not a second failure — it was only ever a chance to say more
     * than the status already says.
     *
     * @param status the HTTP status the answer carried
     * @param body   the refusal's body, as far as the caller was allowed to read it
     * @return the exception to throw
     */
    private SynapseHttpException failure(int status, byte[] body) {
        try (JsonReader reader = codec.reader(new ByteArrayInputStream(body))) {
            String detail = readError(reader);
            if (detail != null) {
                return new SynapseHttpException("OpenAI request failed with HTTP " + status + detail, status);
            }
        } catch (RuntimeException ignored) {
            // Not a JSON document at all — a proxy's HTML, say. The snippet below says what came.
        }
        return new SynapseHttpException(
                "OpenAI request failed with HTTP " + status + ": " + snippet(body), status);
    }

    /**
     * Reads the provider's error document into the detail a failure message carries — the part
     * after its own prefix: {@code ": message [type] (code)}, each member the error object has, in
     * that order, and nothing where it has none. A document that carries no error object answers
     * {@code null}: what to say then belongs to the caller, as does everything about how the body
     * reached a reader in the first place.
     *
     * @param reader the reader, before its first token; the caller owns it
     * @return the detail after the caller's own prefix, or {@code null} when there is none to read
     */
    private static @Nullable String readError(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            return null;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("error".equals(field)) {
                // A scalar where the conventional object belongs says less than the raw body does.
                return reader.token() == JsonReader.Token.START_OBJECT ? errorSuffix(reader) : null;
            }
            reader.skipValue();
        }
        return null;
    }

    /**
     * The detail an error object spells out — {@code ": message [type] (code)}, only the members it
     * has — with the reader positioned on the object's start. Package-visible because a stream's
     * in-frame error renders the same document: one spelling, one home, in the class that both
     * endpoints reach through.
     *
     * @param reader the reader, positioned on the error value
     * @return the detail after the caller's own prefix; never {@code null}
     */
    static String errorSuffix(JsonReader reader) {
        String message = null;
        String type = null;
        String code = null;
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            switch (field) {
                case "message" -> message = errorText(reader);
                case "type" -> type = errorText(reader);
                case "code" -> code = errorText(reader);
                default -> reader.skipValue();
            }
        }
        StringBuilder detail = new StringBuilder();
        if (message != null) {
            detail.append(": ").append(message);
        }
        if (type != null) {
            detail.append(" [").append(type).append(']');
        }
        if (code != null) {
            detail.append(" (").append(code).append(')');
        }
        return detail.toString();
    }

    /** A member's text as the message spells it, or {@code null} where it carries no text. */
    private static @Nullable String errorText(JsonReader reader) {
        JsonReader.Token token = reader.token();
        if (token == JsonReader.Token.START_OBJECT || token == JsonReader.Token.START_ARRAY) {
            // A structured member has no place in the message, and leaving it unread would lose
            // the walk: skip it the way any unmodelled value is passed over.
            reader.skipValue();
            return null;
        }
        return reader.string();
    }

    /**
     * The property name the reader is on, for the member loops below: a loop over an object's
     * members only runs while the reader is on a name, so a null here is a broken reader rather
     * than a document without that name.
     */
    private static String name(JsonReader reader) {
        return Objects.requireNonNull(reader.name(), "the reader is not on a property name");
    }

    private static String snippet(byte[] body) {
        // One long line (a proxy's HTML, say) would otherwise fill the log; a prefix is enough.
        String raw = new String(body, StandardCharsets.UTF_8);
        return raw.length() <= SNIPPET_LIMIT ? raw : raw.substring(0, SNIPPET_LIMIT) + "...";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

}
