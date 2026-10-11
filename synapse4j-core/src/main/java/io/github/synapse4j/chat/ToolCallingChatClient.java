package io.github.synapse4j.chat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolExecutor;
import lombok.NonNull;

/**
 * A {@link ChatClient} that runs the tool-calling loop its delegate leaves to the caller: a
 * response carrying tool calls is executed against the tools the round went out with, the calls
 * and their results are appended to the request, and it goes around again — until a response asks
 * for no tools, or the executor declines the batch.
 *
 * <p>
 * Every round goes to the delegate whole, and the conversation grows on the very request the
 * caller passed: the loop calls {@link ChatClient#continueWith} on that request and appends the
 * batch's results to it, so what the caller holds when the call returns is the conversation with
 * the rounds in it. This class prepares nothing and carries no defaults — whichever client is
 * wrapped owns both, and a registration made here is handed straight to it, so it applies to
 * every round.
 *
 * <p>
 * The context rides along on the request, attached when this client had to create one, and carries
 * the turn: 1 when the round starts, one up per trip around — which is what an executor's turn cap
 * reads. A batch resolves against the tools the round actually went out with, read off the request
 * the delegate recorded on that context: the request's own tools with whatever defaults went with
 * them.
 *
 * <p>
 * {@link #stream} loops too: the rounds' streams are spliced into one sequence of events, and the
 * loop advances inside the pull — a round that runs out with calls outstanding executes its batch
 * there and opens the next round before the next event arrives.
 *
 * <p>
 * A decline ends the round with the response that asked for the calls, them unanswered; a failure
 * the executor throws comes straight out — of {@link #chat(ChatRequest)}, or of the pull on a
 * stream — a checked one under a {@link SynapseException}, the only form either carries.
 */
public class ToolCallingChatClient extends DelegatingChatClient {

    private final ToolExecutor executor;

    /**
     * @param delegate the client every round actually goes to; never {@code null}
     */
    public ToolCallingChatClient(ChatClient delegate) {
        this(delegate, null);
    }

    /**
     * @param delegate the client every round actually goes to; never {@code null}
     * @param executor runs each batch of calls; {@code null} for a
     *                     {@link DefaultToolExecutor} — inline, in order, prefixed failure text,
     *                     no turn cap
     */
    public ToolCallingChatClient(@NonNull ChatClient delegate, @Nullable ToolExecutor executor) {
        super(delegate);
        this.executor = executor != null ? executor : new DefaultToolExecutor();
    }

    /**
     * The tools the round that carried the calls went out with — the request the delegate recorded
     * on the context, defaults and all. The batch resolves against these, not the conversation's
     * own list, so a tool the client lends is one the loop can run.
     */
    private static List<Tool> sentTools(ChatContext context) {
        ChatRequest sent = Objects.requireNonNull(context.getRequest(),
                "the client records the request it sent on the exchange's context");
        return sent.getTools();
    }

    /**
     * The context this exchange runs on, attached to the request so every round finds it: the
     * loop reaches its context through the request, and every round resolves its own anew.
     */
    private static ChatContext attachContext(ChatRequest request) {
        ChatContext context = request.getContext();
        if (context == null) {
            context = new ChatContext();
            request.setContext(context);
        }
        return context;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Keeps going while an answer carries tool calls: each round's batch is executed and its
     * results appended before the next round, so the returned answer is the first that asks for no
     * calls. An executor answering {@code null} stops the loop there, and the answer that carried
     * the calls is returned unanswered.
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatContext context = attachContext(request);
        context.setTurn(1);
        ChatResponse response = getDelegate().chat(request);
        List<ToolCallPart> calls = toolCalls(response);
        while (!calls.isEmpty()) {
            List<ToolResultPart> results = execute(calls, sentTools(context), context);
            if (results == null) {
                return response;
            }
            getDelegate().continueWith(request, response);
            request.getPendingMessages().add(toolResults(results));
            context.setTurn(context.getTurn() + 1);
            response = getDelegate().chat(request);
            calls = toolCalls(response);
        }
        return response;
    }

    /**
     * The batch, with what comes out shaped for this answer form: unchecked failures leave as
     * they are, checked ones arrive under a {@link SynapseException} — {@code chat()} carries
     * nothing else.
     */
    private @Nullable List<ToolResultPart> execute(List<ToolCallPart> calls, List<Tool> available,
            ChatContext context) {
        try {
            return executor.execute(calls, available, context);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new SynapseException("tool batch failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The same loop, answered as one stream: every round goes to the delegate and hands its events
     * on in one sequence, the loop advancing inside the pull — a round that runs out with calls
     * outstanding has its batch executed there, grows the request, counts the turn up, and opens
     * the next round before the next event is handed out. The first round opens here, so a
     * delegate refusing the request still fails this call; a later round's refusal fails the pull.
     * An executor answering {@code null} ends the stream where it stands, the calls unanswered;
     * closing releases the round in progress and starts no further one.
     */
    @Override
    public ChatStream stream(ChatRequest request) {
        attachContext(request).setTurn(1);
        return new LoopingStream(request);
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
        return new ChatMessage(ChatRole.USER.value(), null, results);
    }

    /**
     * The loop as a stream: the rounds' events arrive as one sequence, and the loop runs inside
     * the pull — a round that runs out with calls outstanding has its batch executed right there,
     * grows the request, counts the turn up, and opens the next round before the next event is
     * handed out. The events themselves are the delegate's own, passed through unchanged; this
     * class decides only when the next round starts and which round's answer the caller is shown.
     */
    private final class LoopingStream implements ChatStream {

        private final ChatRequest request;

        /** The round events are currently coming from; replaced as the loop goes around. */
        private volatile ChatStream current;

        private Iterator<ChatStreamEvent> events;

        private @Nullable Iterator<ChatStreamEvent> iterator;

        private boolean exhausted;

        /**
         * The failure that ended the stream — recorded as it came, {@code RuntimeException} or
         * {@code Error} alike — replayed to any later pull: a batch must not run twice, a round
         * must not open twice, and a failure must not leave the round's connection open, so
         * ending here releases it.
         */
        private @Nullable Throwable failure;

        /** Set by {@link #close()} from any thread; read by the consuming thread. */
        private volatile boolean cancelled;

        private LoopingStream(ChatRequest request) {
            this.request = request;
            // The first round opens now, so a delegate refusing the request fails where
            // stream() itself fails; a later round's refusal fails the pull instead.
            this.current = getDelegate().stream(request);
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
                    try {
                        return events.next();
                    } catch (RuntimeException | Error caught) {
                        // A failure this deep never reaches advance()'s own catch; the stream
                        // still ends the same way — released, recorded, replayed.
                        die(caught);
                        throw caught;
                    }
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
         * Whether another event is due, running the loop forward when the current round is over. A
         * decline, or an answer asking for no calls, ends the stream.
         */
        private boolean advance() {
            if (cancelled) {
                throw new IllegalStateException("this stream is closed");
            }
            if (failure != null) {
                // Recorded as it came — only RuntimeException and Error ever land here.
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) failure;
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
                    ChatContext context = attachContext(request);
                    List<ToolResultPart> results = execute(calls, sentTools(context), context);
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
                    getDelegate().continueWith(request, round);
                    request.getPendingMessages().add(toolResults(results));
                    context.setTurn(context.getTurn() + 1);
                    ChatStream next = getDelegate().stream(request);
                    current = next;
                    events = next.iterator();
                    if (cancelled) {
                        // close() caught the new round between the check above and the swap.
                        current.close();
                        throw new IllegalStateException("this stream is closed");
                    }
                }
            } catch (RuntimeException | Error caught) {
                // Whatever ended the pull ends the stream: a later pull replays this failure
                // rather than re-running the batch or re-opening a round, and the round in
                // progress is released so nothing keeps reading behind a dead stream.
                die(caught);
                throw caught;
            }
            return true;
        }

        /**
         * Ends the stream on a failure so nothing keeps reading behind it: the failure is recorded
         * for any later pull and the round in progress released. A close that fails on the way is
         * suppressed onto the original rather than replacing it.
         */
        private void die(Throwable caught) {
            failure = caught;
            try {
                current.close();
            } catch (RuntimeException closeFailure) {
                caught.addSuppressed(closeFailure);
            }
        }

    }

}
