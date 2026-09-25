package io.github.synapse4j.chat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolProvider;
import lombok.NonNull;

/**
 * A {@link ChatClient} that runs its customizers around the exchange: a request customizer before
 * the subclass sees the request, a response customizer before the caller sees the answer, and —
 * inside the streams it builds — the event customizers between their source and their folding.
 *
 * <p>
 * A subclass implements {@link #doChat(ChatRequest)} and {@link #doStream(ChatRequest)} with the
 * exchange itself, and receives {@link #chat(ChatRequest)} and {@link #stream(ChatRequest)} from
 * here, already prepared; the stream it builds in {@code doStream} carries {@link #eventPipeline()}
 * so its events run the chain before they are folded. Extending this class is a convenience, not
 * a requirement: implementing {@link ChatClient} directly is equally valid — running the
 * customizers is then the implementer's to do, and forgetting it fails silently, which is why this
 * class exists.
 *
 * <p>
 * Nothing here is final. A subclass that needs to prepare a request its own way can override
 * {@link #chat(ChatRequest)} or {@link #stream(ChatRequest)} and call {@link #prepare(ChatRequest)}
 * itself.
 */
public abstract class AbstractChatClient implements ChatClient {

    /**
     * Copy-on-write, so a call in flight walks a list no other thread can change under it, and
     * adding a customizer costs a copy only when one is added. All three chains keep the order
     * they were registered in — the list itself is the execution order, so a call in flight walks
     * it as it stands, with no pass to re-sort it.
     */
    private final List<ChatRequestCustomizer> requestCustomizers = new CopyOnWriteArrayList<>();

    private final List<ChatResponseCustomizer> responseCustomizers = new CopyOnWriteArrayList<>();

    private final List<ChatStreamEventCustomizer> eventCustomizers = new CopyOnWriteArrayList<>();

    /**
     * The standing tool set, keyed by name in registration order: a repeated name keeps the
     * slot it first took, so the merge rule's slot is the map's insertion order. The tool
     * containers follow one pattern — a rare writer copies the current container, mutates the
     * copy, and publishes it through the atomic reference in one step; every call reads the
     * reference once (a single {@code get()} into a local, used for the whole merge — two
     * reads could straddle two versions) and traverses that snapshot lock-free. A published
     * container is never mutated again; all changes go through this class's add and remove
     * methods. Names are unique among these — see {@link #addDefaultTool(Tool)}.
     */
    private final AtomicReference<LinkedHashMap<String, Tool>> defaultTools = new AtomicReference<>(
            new LinkedHashMap<>());

    /**
     * The tool sources asked on every call, in registration order, without duplicates —
     * registration records presence, so the same instance registered twice is one source.
     * Same pattern as the defaults above: writers publish a fresh container, readers hold
     * one snapshot.
     */
    private final AtomicReference<LinkedHashSet<ToolProvider>> toolProviders = new AtomicReference<>(
            new LinkedHashSet<>());

    @Override
    public void addChatRequestCustomizer(@NonNull ChatRequestCustomizer customizer) {
        requestCustomizers.add(customizer);
    }

    @Override
    public boolean removeChatRequestCustomizer(@NonNull ChatRequestCustomizer customizer) {
        return requestCustomizers.removeIf(customizer::equals);
    }

    @Override
    public void addChatResponseCustomizer(@NonNull ChatResponseCustomizer customizer) {
        responseCustomizers.add(customizer);
    }

    @Override
    public boolean removeChatResponseCustomizer(@NonNull ChatResponseCustomizer customizer) {
        return responseCustomizers.removeIf(customizer::equals);
    }

    @Override
    public void addChatStreamEventCustomizer(@NonNull ChatStreamEventCustomizer customizer) {
        eventCustomizers.add(customizer);
    }

    @Override
    public boolean removeChatStreamEventCustomizer(@NonNull ChatStreamEventCustomizer customizer) {
        return eventCustomizers.removeIf(customizer::equals);
    }

    /**
     * The chain this client's event customizers form, ready for a stream to run every event
     * through before folding it and handing it out. A subclass builds its stream in
     * {@link #doStream(ChatRequest)} with this; the snapshot is taken now, so a customizer
     * registered after the stream opens joins neither it nor its fold.
     *
     * <p>
     * The chain runs on the thread pulling the events, once per event, between the source and
     * the fold, and its answers are both folded and handed out.
     *
     * @return the chain; never {@code null}
     */
    protected UnaryOperator<ChatStreamEvent> eventPipeline() {
        List<ChatStreamEventCustomizer> snapshot = List.copyOf(eventCustomizers);
        if (snapshot.isEmpty()) {
            return UnaryOperator.identity();
        }
        return event -> runCustomizers(snapshot, event);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A name already registered has its tool replaced at the same slot — a map put keeps the
     * insertion order — so upgrading a tool never shuffles the rest.
     */
    @Override
    public void addDefaultTool(Tool tool) {
        // The one entry where a tool arrives from outside: validate here, never again. The tool
        // itself needs no check — tool.name() below fails right here. The name does: an empty
        // map still accepts a null key, so without this a null name would sail straight in.
        String name = Objects.requireNonNull(tool.name(), "tool name must not be null");
        defaultTools.updateAndGet(registered -> {
            LinkedHashMap<String, Tool> next = new LinkedHashMap<>(registered);
            next.put(name, tool);
            return next;
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Answered under concurrency too: {@code true} only when this call itself took the name
     * out, never when it was already gone.
     */
    @Override
    public boolean removeDefaultTool(String name) {
        Objects.requireNonNull(name, "name must not be null");
        while (true) {
            LinkedHashMap<String, Tool> registered = defaultTools.get();
            if (!registered.containsKey(name)) {
                return false;
            }
            LinkedHashMap<String, Tool> next = new LinkedHashMap<>(registered);
            next.remove(name);
            if (defaultTools.compareAndSet(registered, next)) {
                return true;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addToolProvider(@NonNull ToolProvider provider) {
        toolProviders.updateAndGet(registered -> {
            LinkedHashSet<ToolProvider> next = new LinkedHashSet<>(registered);
            next.add(provider);
            return next;
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Answered under concurrency too: {@code true} only when this call itself took the source
     * out, never when it was already gone.
     */
    @Override
    public boolean removeToolProvider(@NonNull ToolProvider provider) {
        while (true) {
            LinkedHashSet<ToolProvider> registered = toolProviders.get();
            if (!registered.contains(provider)) {
                return false;
            }
            LinkedHashSet<ToolProvider> next = new LinkedHashSet<>(registered);
            next.remove(provider);
            if (toolProviders.compareAndSet(registered, next)) {
                return true;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doChat(ChatRequest)}. A context is resolved for the exchange — the prepared request's
     * own when it carries one, a fresh call-scoped one otherwise, never attached back to the
     * request — and it records the request as sent and the response as received before the answer
     * is handed back on it. The response customizers run last, before the caller.
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatRequest prepared = prepare(request);
        ChatContext context = resolveContext(prepared);
        ChatResponse response = doChat(prepared);
        carryContext(context, response);
        // Each answer is stamped with the context as the pass goes, so the caller's answer and
        // context.getResponse() end up as the same instance even when a customizer answered a copy.
        return runCustomizers(responseCustomizers, response, stamped -> {
            stamped.setContext(context);
            context.setResponse(stamped);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first; the subclass sees the result in
     * {@link #doStream(ChatRequest)}. The context is resolved, the request recorded, and the
     * answer handed back the same way a blocking call does; the response customizers run once,
     * when the stream runs to its end, on a list snapshotted when the stream opens — one
     * registered mid-flight joins neither this stream nor its pass.
     */
    @Override
    public ChatStream stream(ChatRequest request) {
        ChatRequest prepared = prepare(request);
        ChatContext context = resolveContext(prepared);
        ChatStream stream = doStream(prepared);
        carryContext(context, stream.aggregatedResponse());
        if (responseCustomizers.isEmpty()) {
            return stream;
        }
        // The list is already in execution order; the copy is the snapshot across the stream's life.
        return new CustomizedStream(stream, List.copyOf(responseCustomizers), context);
    }

    /**
     * The context this exchange runs on: the prepared request's own when it carries one — the
     * application's, carrying its attributes — and otherwise a fresh call-scoped one. Either
     * way the request is recorded on it as it went out.
     *
     * <p>
     * A subclass that must reach the context through the request itself — a loop driving
     * several exchanges under one — attaches what this method would leave call-scoped.
     */
    protected ChatContext resolveContext(ChatRequest sent) {
        ChatContext context = sent.getContext() != null ? sent.getContext() : new ChatContext();
        context.setRequest(sent);
        return context;
    }

    /**
     * Records the response, adopts any session id the exchange reported into a context of its
     * own — only into one still empty, so the application's value always wins — and hands this
     * context back on the answer: it holds the request, the response and whatever the turn
     * says, and is the instance the caller can carry into the next call.
     */
    private static void carryContext(ChatContext context, ChatResponse response) {
        ChatContext reported = response.getContext();
        if (reported != null && reported != context && context.getSessionId() == null) {
            context.setSessionId(reported.getSessionId());
        }
        context.setResponse(response);
        response.setContext(context);
    }

    /**
     * Runs the chain in registration order: each customizer's answer is the next one's input, and
     * the answer that comes out is the caller's; an answer of {@code null} fails loudly. The hook,
     * when given, runs after each step — where bookkeeping has to follow the answer along, as the
     * context does on a response.
     *
     * @param registrations the chain, in the order it was added
     * @param initial       what the first customizer is given
     * @param afterEach     runs after each answer, or {@code null} for nothing
     * @return the last answer; never {@code null}
     */
    private <T> T runCustomizers(List<? extends ChatCustomizer<T>> registrations, T initial,
            Consumer<T> afterEach) {
        T answer = initial;
        for (ChatCustomizer<T> customizer : registrations) {
            answer = Objects.requireNonNull(customizer.customize(this, answer), "customizer answered null");
            if (afterEach != null) {
                afterEach.accept(answer);
            }
        }
        return answer;
    }

    /** The same, with nothing to do after each step. */
    private <T> T runCustomizers(List<? extends ChatCustomizer<T>> registrations, T initial) {
        return runCustomizers(registrations, initial, null);
    }

    /**
     * Applies this client's own defaults to the request — first, before any customizer runs, so
     * every customizer sees them applied and has the last word on what goes out. Runs exactly once
     * per call. The base assembles the request's tool set from three sources in this order: the
     * default tools registered through {@link #addDefaultTool(Tool)}, what each registered
     * {@link ToolProvider} answers for this call, and the request's own tools — a later source
     * wins by name at the slot the name first took, a new name appends. A subclass with defaults
     * of its own overrides this and must call {@code super.applyDefaults(request)} to keep that
     * merge.
     *
     * @param request the request as the caller built it
     * @return the request to continue with, which may be the one that was given; never
     *         {@code null}
     */
    protected ChatRequest applyDefaults(ChatRequest request) {
        // One get each, held in a local: a second read could land after a registration and
        // straddle two versions — each container is whole on its own, but this call should
        // see one of each.
        LinkedHashMap<String, Tool> defaults = defaultTools.get();
        LinkedHashSet<ToolProvider> providers = toolProviders.get();
        if (defaults.isEmpty() && providers.isEmpty()) {
            return request;
        }
        // A map keyed by name is the merge rule: a put answers the slot a name first took
        // with the later source, and a new name lands at the end.
        LinkedHashMap<String, Tool> merged = new LinkedHashMap<>(defaults);
        for (ToolProvider provider : providers) {
            List<Tool> answered = Objects.requireNonNull(provider.tools(this, request),
                    "tool provider answered null");
            for (Tool tool : answered) {
                // A null key would ride out as a nameless tool — the map would swallow it.
                merged.put(Objects.requireNonNull(tool.name(), "tool name must not be null"), tool);
            }
        }
        if (merged.isEmpty()) {
            // Nothing to lend: the request keeps exactly the tools it brought.
            return request;
        }
        List<Tool> tools = request.getTools();
        for (Tool tool : tools) {
            merged.put(Objects.requireNonNull(tool.name(), "tool name must not be null"), tool);
        }
        tools.clear();
        tools.addAll(merged.values());
        return request;
    }

    /**
     * Applies this client's own defaults, then walks every request customizer in the order it was
     * registered.
     *
     * @param request the request as the caller built it
     * @return the request to send, which is the one that was given when no customizer answered
     *         another; never {@code null}
     * @throws NullPointerException a customizer or {@link #applyDefaults} answered {@code null}
     */
    protected ChatRequest prepare(ChatRequest request) {
        request = Objects.requireNonNull(applyDefaults(request), "applyDefaults answered null");
        return runCustomizers(requestCustomizers, request);
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
    private final class CustomizedStream implements ChatStream {

        private final ChatStream delegate;

        private final List<ChatResponseCustomizer> customizers;

        /** The exchange's context; stamped on every answer the pass hands on. */
        private final ChatContext context;

        private boolean applied;

        private ChatResponse result;

        private CustomizedStream(ChatStream delegate, List<ChatResponseCustomizer> customizers,
                ChatContext context) {
            this.delegate = delegate;
            this.customizers = customizers;
            this.context = context;
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
            result = runCustomizers(customizers, delegate.aggregatedResponse(), stamped -> {
                stamped.setContext(context);
                context.setResponse(stamped);
            });
        }

    }

}
