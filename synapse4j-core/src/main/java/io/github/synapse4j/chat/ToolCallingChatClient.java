package io.github.synapse4j.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ContentPart;
import io.github.synapse4j.data.ToolCallPart;
import io.github.synapse4j.data.ToolResultPart;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.tool.DefaultToolExecutor;
import io.github.synapse4j.tool.ToolExecutor;

/**
 * A {@link ChatClient} that runs the tool-calling loop its inner client leaves to the caller:
 * a response carrying tool calls is executed against the request's tools, the calls and their
 * results are appended to the request, and it goes around again — until a response asks for no
 * tools, or the executor declines the batch.
 *
 * <p>
 * Everything else passes through: the rounds all go to the inner client, so its customizers
 * and defaults run per turn, while this class's own run at the round's start and once on the
 * final answer. {@link #stream} never loops — events come straight from the inner client for
 * the caller to drive by hand.
 *
 * <p>
 * The request grows in place: each turn appends the assistant's answer — the tool calls
 * included — and one message carrying the results, so the caller holds the whole transcript
 * on the request it passed in. The context rides along on the request, attached when this
 * decorator had to create one, and carries the turn: 1 when the round starts, one up per trip
 * around — which is what an executor's turn cap reads.
 *
 * <p>
 * A decline ends the round with the response that asked for the calls, them unanswered; a
 * failure the executor throws comes straight out of {@link #chat(ChatRequest)} — a checked
 * one under a {@link SynapseException}, which is the only form that answer carries.
 */
public class ToolCallingChatClient extends AbstractChatClient {

    private final ChatClient inner;

    private final ToolExecutor executor;

    /**
     * @param inner the client every round actually goes to; never {@code null}
     */
    public ToolCallingChatClient(ChatClient inner) {
        this(inner, null);
    }

    /**
     * @param inner    the client every round actually goes to; never {@code null}
     * @param executor runs each batch of calls; {@code null} for a
     *                     {@link DefaultToolExecutor} — inline, in order, prefixed failure text,
     *                     no turn cap
     */
    public ToolCallingChatClient(ChatClient inner, ToolExecutor executor) {
        this.inner = Objects.requireNonNull(inner, "inner must not be null");
        this.executor = executor != null ? executor : new DefaultToolExecutor();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * This is the loop itself: the first trip out, then — while the answer carries tool calls
     * — execute, append, count the turn up, and go again. An executor answering {@code null}
     * ends the round where it stands.
     */
    @Override
    protected ChatResponse doChat(ChatRequest request) {
        ChatContext context = request.getContext();
        context.setTurn(1);
        ChatResponse response = inner.chat(request);
        List<ToolCallPart> calls = toolCalls(response);
        while (!calls.isEmpty()) {
            List<ToolResultPart> results = execute(calls, request, context);
            if (results == null) {
                return response;
            }
            request.getMessages().add(response.getMessage());
            request.getMessages().add(toolResults(results));
            context.setTurn(context.getTurn() + 1);
            response = inner.chat(request);
            calls = toolCalls(response);
        }
        return response;
    }

    /**
     * The batch, with what comes out shaped for this answer form: unchecked failures leave as
     * they are, checked ones arrive under a {@link SynapseException} — {@code chat()} carries
     * nothing else.
     */
    private List<ToolResultPart> execute(List<ToolCallPart> calls, ChatRequest request, ChatContext context) {
        try {
            return executor.execute(calls, request.getTools(), context);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new SynapseException("tool batch failed", e);
        }
    }

    /** The loop runs for blocking calls only; a stream is handed on untouched. */
    @Override
    protected ChatStream doStream(ChatRequest request) {
        return inner.stream(request);
    }

    /**
     * The loop reaches its context through the request — every inner round runs through
     * {@link ChatClient#chat(ChatRequest)} and resolves anew — so what the base would keep
     * call-scoped is attached here.
     */
    @Override
    protected ChatContext resolveContext(ChatRequest sent) {
        ChatContext context = super.resolveContext(sent);
        if (sent.getContext() == null) {
            sent.setContext(context);
        }
        return context;
    }

    /** The calls the answer carries, in the order it carries them; empty when it asks for none. */
    private static List<ToolCallPart> toolCalls(ChatResponse response) {
        List<ToolCallPart> calls = new ArrayList<>();
        for (ContentPart part : response.getMessage().getParts()) {
            if (part instanceof ToolCallPart call) {
                calls.add(call);
            }
        }
        return calls;
    }

    /** One message answering them all: the protocols pair each result with its call by id. */
    private static ChatMessage toolResults(List<ToolResultPart> results) {
        ChatMessage message = new ChatMessage(ChatRole.TOOL);
        for (ToolResultPart result : results) {
            message.addPart(result);
        }
        return message;
    }

}
