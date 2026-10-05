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

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.data.ChatStreamEvent;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolProvider;
import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A {@link ChatClient} that runs its customizers around the exchange: a request customizer before
 * the subclass sees the request, a response customizer before the caller sees the answer, and —
 * inside the streams it builds — the event customizers between their source and their folding.
 * Recording the conversation is {@link ChatClient#continueWith(ChatRequest, ChatResponse)}'s:
 * what a call sent joins the history when the answer is folded in, once per answer, never on the
 * strength of a send alone. An exchange that fails simply produces no answer to fold, and its
 * pending messages stay where a retry finds them.
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
     * adding a customizer costs a copy only when one is added. The list keeps the order
     * customizers were registered in — the list itself is the execution order, so a call in
     * flight walks it as it stands, with no pass to re-sort it. Every pass reads the same list:
     * a hook a customizer does not override costs its pass nothing.
     */
    private final List<ChatCustomizer> customizers = new CopyOnWriteArrayList<>();

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

    /**
     * The options every call inherits from, or {@code null} when none were set — the common case,
     * which keeps the merge off every call's path. Same pattern as the containers above: a rare
     * writer publishes a whole instance through the reference in one step, and every call reads it
     * once into a local, so a call never sees two versions.
     */
    private final AtomicReference<ChatOptions> defaultOptions = new AtomicReference<>();

    @Override
    public void setDefaultOptions(@NonNull ChatOptions options) {
        defaultOptions.set(options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable ChatOptions defaultOptions() {
        return defaultOptions.get();
    }

    @Override
    public void addChatCustomizer(@NonNull ChatCustomizer customizer) {
        customizers.add(customizer);
    }

    @Override
    public boolean removeChatCustomizer(@NonNull ChatCustomizer customizer) {
        return customizers.removeIf(customizer::equals);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ChatCustomizer> chatCustomizers() {
        return List.copyOf(customizers);
    }

    /**
     * The chain this client's event customizers form, ready for a stream to run every event
     * through before folding it and handing it out. A subclass builds its stream in
     * {@link #doStream(ChatRequest)} with this; the snapshot is taken now, so a customizer
     * registered after the stream opens joins neither it nor its fold.
     *
     * <p>
     * The chain runs on the thread pulling the events, once per event, between the source and
     * the fold, each customizer changing the event in place.
     *
     * @return the chain; never {@code null}
     */
    protected Consumer<ChatStreamEvent> eventPipeline() {
        List<ChatCustomizer> snapshot = List.copyOf(customizers);
        return event -> {
            for (ChatCustomizer customizer : snapshot) {
                customizer.customizeStreamEvent(this, event);
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A name already registered has its tool replaced at the same slot — a map put keeps the
     * insertion order — so upgrading a tool never shuffles the rest.
     */
    @Override
    public void addDefaultTool(@NonNull Tool tool) {
        defaultTools.updateAndGet(registered -> {
            LinkedHashMap<String, Tool> next = new LinkedHashMap<>(registered);
            next.put(tool.name(), tool);
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
    public boolean removeDefaultTool(@NonNull String name) {
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
    public List<Tool> defaultTools() {
        return List.copyOf(defaultTools.get().values());
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
     */
    @Override
    public List<ToolProvider> toolProviders() {
        return List.copyOf(toolProviders.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first — in place, on the very
     * instance the caller passed, so there is no second request to keep track of — and the
     * subclass then sees it in {@link #doChat(ChatRequest)}. A context is resolved for the
     * exchange — the request's own when it carries one, a fresh call-scoped one otherwise, never
     * attached back to the request — and it records the request as sent and the response as
     * received before the answer is handed back on it. Nothing is recorded on the request here:
     * the conversation is recorded when the answer is folded in, through
     * {@link ChatClient#continueWith(ChatRequest, ChatResponse)}, so a call that fails never
     * produces an answer to fold and leaves its pending messages where a retry finds them. The
     * response customizers run last, on the exchange's own answer, and it is that instance the
     * caller receives.
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        prepare(request);
        ChatContext context = resolveContext(request);
        ChatResponse response = doChat(request);
        carryContext(context, response);
        for (ChatCustomizer customizer : customizers) {
            customizer.customizeResponse(this, response);
        }
        return response;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The request goes through {@link #prepare(ChatRequest)} first — in place, as for
     * {@link #chat(ChatRequest)} — and the subclass then sees it in {@link #doStream(ChatRequest)}.
     * The context is resolved, the request recorded, and the aggregated answer stamped with that
     * context — what {@link ChatClient#continueWith(ChatRequest, ChatResponse)} reads off an answer.
     * That stamping and the response customizers' pass both wait for the stream to run to its end,
     * on the wrapper every stream is handed back in — the customizer list is snapshotted when the
     * stream opens, so one registered mid-flight joins neither this stream nor its pass. A stream closed before its
     * last event runs neither: it holds a partial answer, and nothing is recorded for it — the
     * conversation is recorded when the answer is folded in through
     * {@link ChatClient#continueWith(ChatRequest, ChatResponse)}.
     */
    @Override
    public ChatStream stream(ChatRequest request) {
        prepare(request);
        ChatContext context = resolveContext(request);
        ChatStream stream = doStream(request);
        carryContext(context, stream.aggregatedResponse());
        // The list is already in execution order; the copy is the snapshot across the stream's life.
        return new RecordingStream(stream, List.copyOf(customizers), context);
    }

    /**
     * The context this exchange runs on: the request's own when it carries one — the
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
     * Records the response on the context and hands that context back on the answer, so the caller
     * can carry it into the next call. A session id the exchange reported is adopted only into a
     * context that has none of its own, so the application's value always wins.
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
     * Applies this client's own defaults to the request — first, before any customizer runs, so
     * every customizer sees them applied and has the last word on what goes out. Runs exactly once
     * per call. The base fills the request's options from the default options set through
     * {@link #setDefaultOptions(ChatOptions)}, then assembles the request's tool set from three
     * sources in this order: the default tools registered through {@link #addDefaultTool(Tool)},
     * what each registered {@link ToolProvider} answers for this call, and the request's own tools
     * — a later source wins by name at the slot the name first took, a new name appends. A subclass
     * with defaults of its own overrides this and must call {@code super.applyDefaults(request)} to
     * keep that work. The request is changed where it stands: there is no other instance to change.
     *
     * @param request the request as the caller built it
     */
    protected void applyDefaults(ChatRequest request) {
        ChatOptions options = defaultOptions.get();
        if (options != null) {
            request.setOptions(ChatOptions.effective(request.getOptions(), options));
        }
        applyDefaultTools(request);
    }

    /**
     * Merges the standing tool set into the request's own: a later source wins by name at the slot
     * the name first took, and a new name appends. Default tools come first, then each provider's
     * answer, then the request's own.
     *
     * @param request the request as the caller built it
     */
    private void applyDefaultTools(ChatRequest request) {
        // One get each, held in a local: a second read could land after a registration and
        // straddle two versions — each container is whole on its own, but this call should
        // see one of each.
        LinkedHashMap<String, Tool> defaults = defaultTools.get();
        LinkedHashSet<ToolProvider> providers = toolProviders.get();
        if (defaults.isEmpty() && providers.isEmpty()) {
            return;
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
            return;
        }
        List<Tool> tools = request.getTools();
        for (Tool tool : tools) {
            merged.put(Objects.requireNonNull(tool.name(), "tool name must not be null"), tool);
        }
        tools.clear();
        tools.addAll(merged.values());
    }

    /**
     * Applies this client's own defaults, then walks every request customizer in the order it was
     * registered. The request goes out as the instance the caller passed: both steps change it in
     * place rather than answer a replacement, so there is no result to hand back.
     *
     * @param request the request as the caller built it
     */
    protected void prepare(ChatRequest request) {
        applyDefaults(request);
        for (ChatCustomizer customizer : customizers) {
            customizer.customizeRequest(this, request);
        }
    }

    /**
     * Sends one chat request that has already been through {@link #prepare(ChatRequest)}.
     *
     * @param request the request to send, prepared in place; never {@code null}
     * @return the provider's complete answer
     */
    protected abstract ChatResponse doChat(ChatRequest request);

    /**
     * Answers one chat request that has already been through {@link #prepare(ChatRequest)}.
     *
     * @param request the request to answer, prepared in place; never {@code null}
     * @return the streaming answer; never {@code null}
     */
    protected abstract ChatStream doStream(ChatRequest request);

    /**
     * A stream that finishes the exchange once it runs to its end: the aggregated answer is
     * stamped with the exchange's context and the response customizers take their pass over it —
     * in that order, exactly once. The pass runs even with no customizer registered, because the
     * stamping is this wrapper's own job now. A loop that breaks out early leaves it unrun: what
     * it holds is a partial answer, and so is a stream that fails or is closed before its last
     * event. Recording the conversation is not part of it — that happens when the answer is
     * folded in, through {@link ChatClient#continueWith(ChatRequest, ChatResponse)}.
     */
    private final class RecordingStream implements ChatStream {

        private final ChatStream delegate;

        private final List<ChatCustomizer> customizers;

        /** The exchange's context; stamped on every answer the pass hands on. */
        private final ChatContext context;

        private boolean applied;

        private RecordingStream(ChatStream delegate, List<ChatCustomizer> customizers,
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
                        // Drained by pulling past the end: the wrapper still owes its pass.
                        apply();
                        throw drained;
                    }
                }
            };
        }

        @Override
        public ChatResponse aggregatedResponse() {
            return delegate.aggregatedResponse();
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
            ChatResponse aggregated = delegate.aggregatedResponse();
            carryContext(context, aggregated);
            for (ChatCustomizer customizer : customizers) {
                customizer.customizeResponse(AbstractChatClient.this, aggregated);
            }
        }

    }

}
