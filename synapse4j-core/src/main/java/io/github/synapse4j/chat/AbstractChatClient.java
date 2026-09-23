package io.github.synapse4j.chat;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;

/**
 * A {@link ChatClient} that runs its customizers around the exchange: a request customizer before
 * the subclass sees the request, a response customizer before the caller sees the answer.
 *
 * <p>
 * A subclass implements {@link #doChat(ChatRequest)} and {@link #doStream(ChatRequest)} with the
 * exchange itself, and receives {@link #chat(ChatRequest)} and {@link #stream(ChatRequest)} from
 * here, already prepared. Extending this class is a convenience, not a requirement: implementing
 * {@link ChatClient} directly is equally valid — running the customizers is then the implementer's
 * to do, and forgetting it fails silently, which is why this class exists.
 *
 * <p>
 * Nothing here is final. A subclass that needs to prepare a request its own way can override
 * {@link #chat(ChatRequest)} or {@link #stream(ChatRequest)} and call {@link #prepare(ChatRequest)}
 * itself.
 */
public abstract class AbstractChatClient implements ChatClient {

    /**
     * Copy-on-write, so a call in flight walks a list no other thread can change under it, and
     * adding a customizer costs a copy only when one is added. Both customizer kinds get their
     * own list for the same reason.
     */
    private final List<ChatRequestCustomizer> requestCustomizers = new CopyOnWriteArrayList<>();

    private final List<ChatResponseCustomizer> responseCustomizers = new CopyOnWriteArrayList<>();

    @Override
    public void addChatRequestCustomizer(ChatRequestCustomizer customizer) {
        requestCustomizers.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    @Override
    public boolean removeChatRequestCustomizer(ChatRequestCustomizer customizer) {
        return requestCustomizers.remove(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    @Override
    public void addChatResponseCustomizer(ChatResponseCustomizer customizer) {
        responseCustomizers.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    @Override
    public boolean removeChatResponseCustomizer(ChatResponseCustomizer customizer) {
        return responseCustomizers.remove(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doChat(ChatRequest)}. The context the prepared request carries is handed back on the
     * answer — the same instance, so the application's attributes come with it — and the response
     * customizers run last, before the caller.
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatRequest prepared = prepare(request);
        ChatResponse response = doChat(prepared);
        carryContext(prepared, response);
        return customize(response);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doStream(ChatRequest)}. The context the prepared request carries is handed back on
     * the aggregated answer the same way a blocking call hands it back; the response customizers
     * run once, when the stream runs to its end.
     */
    @Override
    public ChatStream stream(ChatRequest request) {
        ChatRequest prepared = prepare(request);
        ChatStream stream = doStream(prepared);
        carryContext(prepared, stream.aggregatedResponse());
        return customizeWhenDrained(stream);
    }

    /**
     * Hands the context of the request that was actually sent back on the answer — that instance,
     * not the caller's original, since a customizer may have answered another request. When none
     * was sent, whatever the exchange put on the answer stands: a provider that reports its own
     * session id fills a context of its own.
     */
    private static void carryContext(ChatRequest sent, ChatResponse response) {
        if (sent.getContext() != null) {
            response.setContext(sent.getContext());
        }
    }

    /** Runs every response customizer in the order they were added; the last one's answer is the caller's. */
    private ChatResponse customize(ChatResponse response) {
        for (ChatResponseCustomizer customizer : responseCustomizers) {
            response = Objects.requireNonNull(customizer.customize(response), "customizer answered null");
        }
        return response;
    }

    /**
     * The same pass for a streamed answer, taken when the stream runs to its end — the first
     * moment the aggregated answer is whole. With nothing registered the stream is handed on
     * untouched, and the list is snapshotted now so a customizer added mid-flight does not join
     * an exchange already under way.
     */
    private ChatStream customizeWhenDrained(ChatStream stream) {
        if (responseCustomizers.isEmpty()) {
            return stream;
        }
        return new CustomizedStream(stream, List.copyOf(responseCustomizers));
    }

    /**
     * Applies every request customizer, in the order they were added.
     *
     * @param request the request as the caller built it
     * @return the request to send, which is the one that was given when no customizer answered
     *         another; never {@code null}
     * @throws NullPointerException a customizer answered {@code null}
     */
    protected ChatRequest prepare(ChatRequest request) {
        for (ChatRequestCustomizer customizer : requestCustomizers) {
            request = Objects.requireNonNull(customizer.customize(request), "customizer answered null");
        }
        return request;
    }

    /**
     * Sends one chat request that has already been through {@link #prepare(ChatRequest)}.
     *
     * @param request the prepared request; never {@code null}
     * @return the provider's complete answer
     */
    protected abstract ChatResponse doChat(ChatRequest request);

    /**
     * Answers one chat request that has already been through {@link #prepare(ChatRequest)}.
     *
     * @param request the prepared request; never {@code null}
     * @return the streaming answer; never {@code null}
     */
    protected abstract ChatStream doStream(ChatRequest request);

    /**
     * A stream that hands the aggregated answer to the response customizers once it runs to its
     * end. A loop that breaks out early leaves them unrun: what it holds is a partial answer, and
     * so is a stream that fails or is closed before its last event.
     */
    private static final class CustomizedStream implements ChatStream {

        private final ChatStream delegate;

        private final List<ChatResponseCustomizer> customizers;

        private boolean applied;

        private ChatResponse result;

        private CustomizedStream(ChatStream delegate, List<ChatResponseCustomizer> customizers) {
            this.delegate = delegate;
            this.customizers = customizers;
        }

        @Override
        public Iterator<ChatStreamEvent> iterator() {
            Iterator<ChatStreamEvent> events = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    boolean more = events.hasNext();
                    if (!more) {
                        apply();
                    }
                    return more;
                }

                @Override
                public ChatStreamEvent next() {
                    try {
                        return events.next();
                    } catch (NoSuchElementException drained) {
                        // Drained by pulling past the end: the customizers still owe their pass.
                        apply();
                        throw drained;
                    }
                }
            };
        }

        @Override
        public ChatResponse aggregatedResponse() {
            return result != null ? result : delegate.aggregatedResponse();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void apply() {
            if (applied) {
                return;
            }
            applied = true;
            ChatResponse response = delegate.aggregatedResponse();
            for (ChatResponseCustomizer customizer : customizers) {
                response = Objects.requireNonNull(customizer.customize(response), "customizer answered null");
            }
            result = response;
        }

    }

}
