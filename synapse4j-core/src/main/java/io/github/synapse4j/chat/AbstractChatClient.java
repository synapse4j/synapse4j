package io.github.synapse4j.chat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;

/**
 * A {@link ChatClient} that runs its customizers before the subclass sees the request.
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
     * adding a customizer costs a copy only when one is added.
     */
    private final List<ChatRequestCustomizer> customizers = new CopyOnWriteArrayList<>();

    @Override
    public void addChatRequestCustomizer(ChatRequestCustomizer customizer) {
        customizers.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    @Override
    public boolean removeChatRequestCustomizer(ChatRequestCustomizer customizer) {
        return customizers.remove(Objects.requireNonNull(customizer, "customizer must not be null"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doChat(ChatRequest)}.
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        return doChat(prepare(request));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doStream(ChatRequest)}.
     */
    @Override
    public ChatStream stream(ChatRequest request) {
        return doStream(prepare(request));
    }

    /**
     * Applies every customizer, in the order they were added.
     *
     * @param request the request as the caller built it
     * @return the request to send, which is the one that was given when no customizer answered
     *         another; never {@code null}
     * @throws NullPointerException a customizer answered {@code null}
     */
    protected ChatRequest prepare(ChatRequest request) {
        for (ChatRequestCustomizer customizer : customizers) {
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

}
