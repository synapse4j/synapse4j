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
 * The OpenAI Responses client: speaks {@code POST /responses} and answers in the shared chat
 * model. The transport flow it shares with {@link OpenAiCompletionsChatClient} — the headers, the
 * validation, the configuration snapshot, the refusal path — lives in
 * {@link AbstractOpenAiChatClient}; what is left here is this protocol's own wire.
 *
 * <p>
 * The wire shape is pinned by literal names — no codec-level setting, a naming strategy among
 * them, can rename a field, and a member whose value is not set is never emitted — so whichever
 * JSON library the application chose, the bytes on the wire are exactly this protocol's spelling.
 * The conversation goes out as this protocol's flat array of input items rather than as messages,
 * and the response is walked token by token, every field this module does not model kept in the
 * extras of the node it came from.
 *
 * <p>
 * A streamed answer asks for it with a {@code stream} member of its own; the frames become events
 * one for one, and the fold makes the assembled answer the same answer a blocking call returns.
 */
public class OpenAiResponsesChatClient extends AbstractOpenAiChatClient {

    /**
     * Creates the client.
     *
     * @param http   the transport to send through; must not be {@code null}
     * @param codec  the application's JSON codec; must not be {@code null}
     * @param config the family configuration to send with; must not be {@code null}
     */
    public OpenAiResponsesChatClient(HttpClient http, JsonCodec codec, OpenAiConfig config) {
        super(http, codec, config);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This protocol continues a conversation by naming the response the server last produced, so
     * folding an answer in means recording its id as that name: the writer sends it as
     * {@code previous_response_id}, and while it is there sends only the pending messages — the
     * history the chain already holds must not go out a second time.
     *
     * <p>
     * An answer without an id cannot be chained from. Keeping the name of an earlier response
     * would make the next call fork the chain from a point the server has already moved past,
     * dropping everything sent since; the name comes off instead, and the next call re-sends the
     * conversation rather than lose a turn of it.
     */
    @Override
    public void continueWith(ChatRequest request, ChatResponse answer) {
        super.continueWith(request, answer);
        String id = answer.getId();
        if (id != null) {
            request.getOptions().getExtras().put(ResponsesWriter.PREVIOUS_RESPONSE_ID, id);
        } else {
            request.getOptions().getExtras().remove(ResponsesWriter.PREVIOUS_RESPONSE_ID);
        }
    }

    @Override
    protected String endpoint() {
        return "/responses";
    }

    @Override
    protected String protocol() {
        return "Responses request";
    }

    @Override
    protected void write(ChatRequest request, JsonWriter writer, OpenAiConfig config, boolean streaming) {
        ResponsesWriter document = new ResponsesWriter(codec);
        if (streaming) {
            document.writeStreaming(request, writer);
        } else {
            document.write(request, writer);
        }
    }

    @Override
    protected ChatResponse read(JsonReader reader, OpenAiConfig config) {
        return new ResponsesReader().read(reader);
    }

    @Override
    protected ChatStream openStream(SseEventStream events, AutoCloseable closeAction, OpenAiConfig config) {
        // The Responses stream's reader takes no endpoint conventions, so the config has nothing
        // to do here — the hook keeps the same shape on both protocols anyway.
        return new ResponsesStream(codec, events, eventPipeline(), closeAction);
    }

}
