package io.github.synapse4j.chat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatMessage;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatRole;
import io.github.synapse4j.data.ChatStreamEvent;
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
 * final answer. Event customizers registered on this decorator are handed to the inner client
 * as they are registered — its streams fold the events, and that is where the chain has to run.
 * {@link #stream} loops too: the rounds' streams are spliced into one sequence of events, and
 * the loop advances inside the pull — a round that runs out with calls outstanding executes
 * its batch there and opens the next round before the next event arrives.
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
 * failure the executor throws comes straight out — of {@link #chat(ChatRequest)}, or of the
 * pull on a stream — a checked one under a {@link SynapseException}, the only form either
 * carries.
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
     * Handed straight to the inner client — its streams are where the events are folded, and
     * the chain has to run there.
     */
    @Override
    public void addChatStreamEventCustomizer(ChatStreamEventCustomizer customizer) {
        inner.addChatStreamEventCustomizer(customizer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The registration was handed to the inner client, and it comes off there.
     */
    @Override
    public boolean removeChatStreamEventCustomizer(ChatStreamEventCustomizer customizer) {
        return inner.removeChatStreamEventCustomizer(customizer);
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

    /**
     * The same loop, answered as one stream: every round goes to the inner client and hands
     * its events on in one sequence, the loop advancing inside the pull — a round that runs
     * out with calls outstanding has its batch executed there, grows the request, counts the
     * turn up, and opens the next round before the next event is handed out. The first round
     * opens here, so a provider refusing the request still fails this call; a later round's
     * refusal fails the pull. An executor answering {@code null} ends the stream where it
     * stands, the calls unanswered; closing releases the round in progress and starts no
     * further one.
     */
    @Override
    protected ChatStream doStream(ChatRequest request) {
        request.getContext().setTurn(1);
        return new LoopingStream(request);
    }

    /**
     * The loop reaches its context through the request — every inner round runs through
     * {@link ChatClient#chat(ChatRequest)} or {@link ChatClient#stream(ChatRequest)} and
     * resolves anew — so what the base would keep call-scoped is attached here.
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

    /**
     * The loop as a stream: the rounds' events arrive as one sequence, and the loop runs inside
     * the pull — a round that runs out with calls outstanding has its batch executed right there,
     * grows the request, counts the turn up, and opens the next round before the next event is
     * handed out. The events themselves are the inner streams' own, passed through unchanged;
     * this class decides only when the next round starts and which round's answer the caller
     * is shown.
     */
    private final class LoopingStream implements ChatStream {

        private final ChatRequest request;

        /** The round events are currently coming from; replaced as the loop goes around. */
        private volatile ChatStream current;

        private Iterator<ChatStreamEvent> events;

        private Iterator<ChatStreamEvent> iterator;

        private boolean exhausted;

        /**
         * The failure that ended the stream, replayed to any later pull: a batch must not run
         * twice, and a round must not open twice, because a caller pulled again after a failure.
         */
        private RuntimeException failure;

        /** Set by {@link #close()} from any thread; read by the consuming thread. */
        private volatile boolean cancelled;

        private LoopingStream(ChatRequest request) {
            this.request = request;
            // The first round opens now, so a provider refusing the request fails where
            // stream() itself fails; a later round's refusal fails the pull instead.
            this.current = inner.stream(request);
            this.events = current.iterator();
        }

        @Override
        public Iterator<ChatStreamEvent> iterator() {
            if (iterator != null) {
                throw new IllegalStateException("this stream has already been iterated");
            }
            iterator = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return advance();
                }

                @Override
                public ChatStreamEvent next() {
                    if (!advance()) {
                        throw new NoSuchElementException("the stream is exhausted");
                    }
                    return events.next();
                }
            };
            return iterator;
        }

        @Override
        public ChatResponse aggregatedResponse() {
            return current.aggregatedResponse();
        }

        @Override
        public void close() {
            cancelled = true;
            current.close();
        }

        /**
         * Whether another event is due, running the loop forward when the current round is
         * over: while the round's stream has nothing left, the calls it ended with — if any —
         * run, the request grows, the turn counts up, and the next round opens. A decline, or
         * an answer asking for no calls, ends the stream.
         */
        private boolean advance() {
            if (cancelled) {
                throw new IllegalStateException("this stream is closed");
            }
            if (failure != null) {
                throw failure;
            }
            if (exhausted) {
                return false;
            }
            try {
                while (!events.hasNext()) {
                    ChatResponse round = current.aggregatedResponse();
                    List<ToolCallPart> calls = toolCalls(round);
                    if (calls.isEmpty()) {
                        exhausted = true;
                        return false;
                    }
                    List<ToolResultPart> results = execute(calls, request, request.getContext());
                    if (results == null) {
                        // The executor declined: the round ends where it stands, calls unanswered.
                        exhausted = true;
                        return false;
                    }
                    if (cancelled) {
                        // Released while the batch ran — the batch itself is not undone, but
                        // no further round starts.
                        throw new IllegalStateException("this stream is closed");
                    }
                    request.getMessages().add(round.getMessage());
                    request.getMessages().add(toolResults(results));
                    request.getContext().setTurn(request.getContext().getTurn() + 1);
                    ChatStream next = inner.stream(request);
                    current = next;
                    events = next.iterator();
                    if (cancelled) {
                        // close() caught the new round between the check above and the swap.
                        current.close();
                        throw new IllegalStateException("this stream is closed");
                    }
                }
            } catch (RuntimeException thrown) {
                // Whatever ended the pull ends the stream: a later pull replays this failure
                // rather than re-running the batch or re-opening a round.
                failure = thrown;
                throw thrown;
            }
            return true;
        }

    }

}
