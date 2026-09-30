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
 * one for one, and the fold assembles them back into the turn a blocking call returns.
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
     * This protocol continues a conversation by naming the response the server last produced —
     * {@code previous_response_id} — and while that name stands sends only the pending messages,
     * because the history the chain already holds is what the server holds. Folding an answer in
     * therefore decides three things:
     *
     * <ul>
     * <li>An answer the chain can move to — this call keeps its responses (the {@code store}
     * member is not {@code false}, in the request's extras or in the family configuration) and
     * the answer carries an id — folds in through the default: what the call sent joins the
     * history, the answer joins it, and the id becomes the name the next call sends.</li>
     * <li>An answer the chain cannot move to, with no name on the request yet, folds in through
     * the default and writes nothing: nothing is chained, the whole conversation goes out
     * anyway, so recording it is free.</li>
     * <li>An answer the chain cannot move to, with a name already on the request, does not
     * archive: the name still points at the older response, so the next call sends only the
     * pending messages, and anything archived here would be skipped while it does. The answer
     * joins the pending messages instead, the name stays where it is, and the answer's context is
     * adopted the way the default adopts it.</li>
     * </ul>
     *
     * <p>
     * The id alone never decides: a response carries one whether or not the endpoint kept it, and
     * a chain can only be built on a response that was kept — so the {@code store} reading
     * resolves first, and only an answer that passes both readings is written as the next name.
     */
    @Override
    public void continueWith(ChatRequest request, ChatResponse answer) {
        boolean anchored = request.getOptions().getExtras().contains(ResponsesWriter.PREVIOUS_RESPONSE_ID);
        if (stored(request) && answer.getId() != null) {
            super.continueWith(request, answer);
            request.getOptions().getExtras().put(ResponsesWriter.PREVIOUS_RESPONSE_ID, answer.getId());
        } else if (!anchored) {
            super.continueWith(request, answer);
        } else {
            // The name did not move, so the next call still sends only the pending messages: what
            // was sent stays among them — archived here, it would be skipped — and the answer
            // joins them rather than the history the chain already covers.
            if (request.getContext() == null && answer.getContext() != null) {
                request.setContext(answer.getContext());
            }
            request.addPendingMessage(answer.getMessage());
        }
    }

    /**
     * Whether this call asks the endpoint to keep its answers, in the order the decision is
     * resolved: the {@code store} member the application set in this request's extras — the
     * member goes out on the wire whichever way, so it is also the decision the chain lives by —
     * then the family configuration, then the endpoint's own default, which both providers fix as
     * keep. A member that is not a boolean cannot be read as a decision and is passed over.
     *
     * @param request the request being folded into; never {@code null}
     * @return whether this call keeps its answers
     */
    private boolean stored(ChatRequest request) {
        Object store = request.getOptions().getExtras().get(ResponsesWriter.STORE);
        if (store instanceof Boolean value) {
            return value;
        }
        Boolean configured = config().getStoreResponses();
        return configured == null || configured;
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
        ResponsesWriter document = new ResponsesWriter(codec, config);
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
