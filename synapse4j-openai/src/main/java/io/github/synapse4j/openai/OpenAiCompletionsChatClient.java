package io.github.synapse4j.openai;

import io.github.synapse4j.chat.ChatStream;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.SseEventStream;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonReader;
import io.github.synapse4j.json.JsonWriter;

/**
 * The OpenAI chat-completions client: speaks {@code POST /chat/completions} and answers in the
 * shared chat model. The transport flow it shares with {@link OpenAiResponsesChatClient} — the
 * headers, the validation, the configuration snapshot, the refusal path — lives in
 * {@link AbstractOpenAiChatClient}; what is left here is this protocol's own wire.
 *
 * <p>
 * The wire shape is pinned by literal names — no codec-level setting, a naming strategy among
 * them, can rename a field, and a member whose value is not set is never emitted — so whichever
 * JSON library the application chose, the bytes on the wire are exactly this protocol's spelling.
 * Writing the protocol's own names is also what takes the mapper's naming knob away: a knob that
 * could rename a field has no work left to do here. The request is assembled as the object it goes
 * out as — one map per node, the members this module models written into it with the node's extras
 * merged over them — and the response's document is walked token by token, every field this module
 * does not model kept in the extras of the node it came from.
 *
 * <p>
 * A streamed answer asks for it with {@code stream} and {@code stream_options} members of its own;
 * the frames become events one for one, and the fold assembles them back into the turn a blocking
 * call returns.
 */
public class OpenAiCompletionsChatClient extends AbstractOpenAiChatClient {

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the family configuration to send with; must not be {@code null}
     */
    public OpenAiCompletionsChatClient(HttpClient http, JsonCodec codec, OpenAiConfig config) {
        super(http, codec, config);
    }

    @Override
    protected String endpoint() {
        return "/chat/completions";
    }

    @Override
    protected String protocol() {
        return "chat completion";
    }

    @Override
    protected void write(ChatRequest request, JsonWriter writer, OpenAiConfig config, boolean streaming) {
        CompletionsWriter document = new CompletionsWriter(config);
        if (streaming) {
            document.writeStreaming(request, writer);
        } else {
            document.write(request, writer);
        }
    }

    @Override
    protected ChatResponse read(JsonReader reader, OpenAiConfig config) {
        return new CompletionsReader(config).read(reader);
    }

    @Override
    protected ChatStream openStream(SseEventStream events, AutoCloseable closeAction, OpenAiConfig config) {
        return new CompletionsStream(codec, events, eventPipeline(), closeAction, config);
    }

}
