package io.github.synapse4j.openai;

import java.util.List;
import java.util.Objects;

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
 * The family the two OpenAI endpoints belong to: chat completions and Responses are two protocols
 * over one transport flow — one URL below a base URL, one set of headers, one validation before
 * anything goes out, one refusal path, one way of streaming an accepted answer. That flow is the
 * {@link AbstractHttpChatClient}'s, so a fix to it lands on both endpoints at once; what is left
 * here is what differs between them, on the wire and nowhere else: the endpoint path, the headers
 * this family's configuration adds, the document each writes, the answer each reads, and the stream
 * each builds.
 *
 * <p>
 * The request is written straight into the codec's {@link JsonWriter} and the response is walked
 * token by token through its {@link JsonReader}: neither direction builds the document as a
 * structure first, and a member whose value is not set is never emitted. A field this module does
 * not model is kept in the extras of the node it came from rather than dropped.
 *
 * <p>
 * The headers this family adds — the key, when one is configured, and the organization and project
 * — go on before the caller's own, which are applied last: a caller can override anything, and can
 * supply an {@code Authorization} of its own for an endpoint whose scheme differs.
 */
public abstract class AbstractOpenAiChatClient extends AbstractHttpChatClient<OpenAiConfig> {

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the family configuration to send with; must not be {@code null}
     */
    protected AbstractOpenAiChatClient(@NonNull HttpClient http, @NonNull JsonCodec codec,
            @NonNull OpenAiConfig config) {
        super(http, codec, config);
    }

    /**
     * The path this protocol's endpoint sits at, below the configured base URL.
     *
     * @return the endpoint path; never {@code null}
     */
    protected abstract String endpoint();

    @Override
    protected String url(OpenAiConfig config) {
        return withoutTrailingSlash(config.getBaseUrl()) + endpoint();
    }

    @Override
    protected void headers(HttpRequest request, OpenAiConfig config) {
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            request.getHeaders().put("Authorization", List.of("Bearer " + config.getApiKey()));
        }
        if (config.getOrganization() != null) {
            request.getHeaders().put("OpenAI-Organization", List.of(config.getOrganization()));
        }
        if (config.getProject() != null) {
            request.getHeaders().put("OpenAI-Project", List.of(config.getProject()));
        }
    }

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
    @Override
    protected abstract void write(ChatRequest request, JsonWriter writer, OpenAiConfig config, boolean streaming);

    /**
     * Walks a successful response body into the shared answer, reading the protocol's document
     * through the reader the transport's stream was wrapped in.
     *
     * @param reader the reader, before its first token; owned by this call, and closed with it
     * @param config the endpoint's conventions for this exchange
     * @return the answer; never {@code null}
     */
    @Override
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
    @Override
    protected abstract ChatStream openStream(SseEventStream events, AutoCloseable closeAction, OpenAiConfig config);

    @Override
    protected @Nullable String errorDetail(JsonReader reader) {
        return errorSuffix(reader);
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

    /**
     * The property name the reader is on, for the member loop above: a loop over an object's members
     * only runs while the reader is on a name, so a null here is a broken reader rather than a
     * document without that name.
     */
    private static String name(JsonReader reader) {
        return Objects.requireNonNull(reader.name(), "the reader is not on a property name");
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

}
