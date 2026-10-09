package io.github.synapse4j.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

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
 * The transport flow behind every provider that speaks a JSON-over-HTTP chat API: one request posted
 * to one URL, one set of headers, one check before anything goes out, one way of reading the status
 * before the body, one refusal path, and one way of streaming what an accepted answer carries.
 *
 * <p>
 * The flow is this class's and is not a hook: what a protocol decides is what it puts on the wire and
 * what it makes of the wire — the URL, the headers its configuration adds, the document it writes,
 * the answer it reads, the stream it builds, and how its error document reads. Everything around
 * those stays put: the request is written straight into the codec's {@link JsonWriter} and the answer
 * walked token by token through its {@link JsonReader}, so neither direction is built as a structure
 * first; the caller's own headers are applied last, so a caller can override anything the module set;
 * the status is read before the body is touched, because the body reaches the caller once — a
 * non-2xx answer is buffered, within a bound, for the detail it carries, and only a 2xx one is
 * streamed; a streamed answer hands the response to the {@link ChatStream}, whose closing is what
 * cancels an answer still in flight, and closes it on any other way out.
 *
 * <p>
 * Extending this is a choice, not an obligation. A protocol whose exchange is not this shape extends
 * {@link AbstractChatClient} directly rather than bending itself to fit here.
 *
 * <p>
 * The client is stateless apart from the configuration and safe to share across threads: an exchange
 * reads the configuration once into a local, so a {@link #setConfig} landing mid-call cannot send one
 * request partly under the configuration it started with and partly under the one that replaced it.
 *
 * @param <C> the configuration this client sends with
 */
public abstract class AbstractHttpChatClient<C> extends AbstractChatClient {

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
     * The configuration in effect, replaceable while the client is in use. The reference follows the
     * same pattern as the tool containers in {@link AbstractChatClient}: a rare writer publishes a
     * whole new instance through it in one step, and every exchange reads it once into a local — two
     * reads could straddle two versions, and a request must not go out partly under one configuration
     * and partly under another.
     */
    private final AtomicReference<C> config;

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the configuration to send with; must not be {@code null}
     */
    protected AbstractHttpChatClient(@NonNull HttpClient http, @NonNull JsonCodec codec,
            @NonNull C config) {
        this.http = http;
        this.codec = codec;
        this.config = new AtomicReference<>(config);
    }

    /**
     * Replaces the configuration this client sends with. In flight exchanges keep the instance they
     * started with; the next one sees the new value.
     *
     * @param config the new configuration; must not be {@code null}
     */
    public void setConfig(@NonNull C config) {
        this.config.set(config);
    }

    /**
     * The configuration in effect. A subclass that has to consult it outside an exchange reads it here
     * once, the way an exchange snapshots it: two reads could straddle a {@link #setConfig} and answer
     * under two configurations.
     *
     * @return the configuration in effect; never {@code null}
     */
    protected final C config() {
        return config.get();
    }

    /**
     * The URL this exchange posts to, query string and all: joining the configured base URL to the
     * protocol's endpoint path is the subclass's, since what a base URL carries differs — a path
     * prefix, a version segment, a query parameter.
     *
     * @param config the configuration in effect for this exchange; never {@code null}
     * @return the URL to post to; never {@code null}
     */
    protected abstract String url(C config);

    /**
     * Adds the headers this protocol's configuration puts on the request.
     *
     * <p>
     * {@code Content-Type} is already set when this runs, and the caller's own headers are applied
     * after it returns, so what is added here a caller can override — which is what lets an endpoint
     * with a scheme of its own be reached without this module knowing about it.
     *
     * @param request the request being built; never {@code null}
     * @param config  the configuration in effect for this exchange; never {@code null}
     */
    protected abstract void headers(HttpRequest request, C config);

    /**
     * Writes the request document — the blocking or the streaming spelling, as the flag says — into
     * the writer the transport handed over. The document is the protocol's own: its members, its
     * names, and which writer builds it.
     *
     * @param request   the request to translate; never {@code null}
     * @param writer    the writer to write into; owned by this call, and closed with it
     * @param config    the configuration in effect for this exchange; never {@code null}
     * @param streaming whether the document is the one a streamed answer goes out with
     */
    protected abstract void write(ChatRequest request, JsonWriter writer, C config, boolean streaming);

    /**
     * Walks a successful response body into the shared answer, reading the protocol's document
     * through the reader the transport's stream was wrapped in.
     *
     * @param reader the reader, before its first token; owned by this call, and closed with it
     * @param config the configuration in effect for this exchange; never {@code null}
     * @return the answer; never {@code null}
     */
    protected abstract ChatResponse read(JsonReader reader, C config);

    /**
     * Builds the streaming answer over an accepted response: the frames, the fold, and what releasing
     * the stream releases are the protocol's to wire. The codec and the event pipeline are reachable
     * where this runs, so the exchange hands the stream nothing it cannot already reach itself.
     *
     * @param events      the response's event frames, in arrival order
     * @param closeAction what releasing the stream does — closing the HTTP response behind it
     * @param config      the configuration in effect for this exchange; never {@code null}
     * @return the streaming answer; never {@code null}
     */
    protected abstract ChatStream openStream(SseEventStream events, AutoCloseable closeAction, C config);

    /**
     * The detail the error document's error object spells out, with the reader positioned on the
     * object's start.
     *
     * <p>
     * The walk to that object is this class's; what it says is the protocol's — it is the same
     * rendering a stream's in-frame error needs, so a module keeps one spelling and reaches it from
     * both. Reading nothing and answering {@code null} is a valid answer: a body that is not the
     * conventional error document is one the failure message shows a snippet of instead.
     *
     * @param reader the reader, positioned on the error object's start
     * @return the detail to append after the status, or {@code null} when there is none to read
     */
    protected @Nullable String errorDetail(JsonReader reader) {
        reader.skipValue();
        return null;
    }

    /**
     * Refuses a call the provider cannot even be asked — the caller's mistake, found before anything
     * goes out, and so an {@link IllegalArgumentException} rather than a transport failure.
     *
     * <p>
     * The default requires a model. A protocol with more of its own to check overrides this.
     *
     * @param request the request about to be sent; never {@code null}
     */
    protected void requireCallable(ChatRequest request) {
        require(request.getOptions().getModel() != null && !request.getOptions().getModel().isBlank(),
                "options.model is required");
    }

    @Override
    protected final ChatResponse doChat(ChatRequest request) {
        // One snapshot for the whole exchange: a setConfig landing mid-call must not send this
        // request partly under the old configuration and partly under the new.
        C config = this.config.get();
        requireCallable(request);

        HttpRequest httpRequest = buildHttpRequest(url(config), config, request, out -> {
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
            throw new SynapseException(getClass().getSimpleName() + " failed: response could not be read", e);
        }
    }

    @Override
    protected final ChatStream doStream(ChatRequest request) {
        // The same once-per-exchange snapshot the blocking path takes.
        C config = this.config.get();
        requireCallable(request);

        HttpRequest httpRequest = buildHttpRequest(url(config), config, request, out -> {
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
                    throw new SynapseException(getClass().getSimpleName() + " answered " + status
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
     * The HTTP request both ways of asking share: one URL, one set of headers, the caller's applied
     * last. Only the body differs between them, so it is the one thing handed in beside the URL.
     */
    private HttpRequest buildHttpRequest(String url, C config, ChatRequest request, HttpBody body) {
        HttpRequest httpRequest = new HttpRequest(url);
        httpRequest.setMethod(HttpRequest.POST);
        httpRequest.getHeaders().put("Content-Type", List.of("application/json"));
        headers(httpRequest, config);
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
            throw new SynapseException(getClass().getSimpleName() + " failed: response could not be read", e);
        }
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
                return new SynapseHttpException(
                        getClass().getSimpleName() + " failed with HTTP " + status + detail, status);
            }
        } catch (RuntimeException ignored) {
            // Not a JSON document at all — a proxy's HTML, say. The snippet below says what came.
        }
        return new SynapseHttpException(
                getClass().getSimpleName() + " failed with HTTP " + status + ": " + snippet(body), status);
    }

    /**
     * Walks the conventional error document down to its error object and hands that to
     * {@link #errorDetail(JsonReader)}. A document that carries no error object answers {@code null}:
     * what to say then belongs to the caller, as does everything about how the body reached a reader
     * in the first place.
     */
    private @Nullable String readError(JsonReader reader) {
        if (reader.nextToken() != JsonReader.Token.START_OBJECT) {
            return null;
        }
        while (reader.nextToken() != JsonReader.Token.END_OBJECT) {
            String field = name(reader);
            reader.nextToken();
            if ("error".equals(field)) {
                if (reader.token() != JsonReader.Token.START_OBJECT) {
                    // A scalar where the conventional object belongs says less than the raw body does.
                    reader.skipValue();
                    return null;
                }
                return errorDetail(reader);
            }
            reader.skipValue();
        }
        return null;
    }

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
     * The property name the reader is on, for the member loop above: a loop over an object's members
     * only runs while the reader is on a name, so a null here is a broken reader rather than a
     * document without that name.
     */
    private static String name(JsonReader reader) {
        return Objects.requireNonNull(reader.name(), "the reader is not on a property name");
    }

    private static String snippet(byte[] body) {
        // One long line (a proxy's HTML, say) would otherwise fill the log; a prefix is enough.
        String raw = new String(body, StandardCharsets.UTF_8);
        return raw.length() <= SNIPPET_LIMIT ? raw : raw.substring(0, SNIPPET_LIMIT) + "...";
    }

    /**
     * The base URL as a URL an endpoint path can be appended to: a trailing slash is dropped, so a
     * configured {@code https://host/} does not put a doubled slash in the path.
     *
     * @param baseUrl the configured base URL; never {@code null}
     * @return the base URL without a trailing slash
     */
    protected static String withoutTrailingSlash(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }

    /**
     * Refuses a call this client cannot make, naming what is wrong: the mistake is the caller's, so
     * the answer is an {@link IllegalArgumentException} and the exception carries the reason.
     *
     * @param condition what must hold for the call to be made
     * @param message   what to say when it does not
     */
    protected static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

}
