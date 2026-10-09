package io.github.synapse4j.chat;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.data.ChatResponse;
import io.github.synapse4j.json.JsonSchemaCustomizer;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolProvider;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A chat client that stands in for another: every method is handed to the client it wraps, so a
 * subclass overrides only what it means to change and nothing else takes a second path.
 *
 * <p>
 * Everything travels to the delegate — the two calls, the five registrations, their removals and
 * their listings, and {@link ChatClient#continueWith}. The delegate is the client that prepares
 * and sends; this one holds no defaults and runs no customizer of its own, so what a call adds to
 * the delegate applies here too. A subclass that drives several exchanges under one, or wraps a
 * step around each, overrides the calls it needs and leaves the rest here.
 */
@RequiredArgsConstructor
public class DelegatingChatClient implements ChatClient {

    /** The client every call and registration is handed to. */
    @Getter(AccessLevel.PROTECTED)
    @NonNull
    private final ChatClient delegate;

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(request);
    }

    @Override
    public ChatStream stream(ChatRequest request) {
        return delegate.stream(request);
    }

    @Override
    public void continueWith(ChatRequest request, ChatResponse answer) {
        delegate.continueWith(request, answer);
    }

    @Override
    public void addChatCustomizer(ChatCustomizer customizer) {
        delegate.addChatCustomizer(customizer);
    }

    @Override
    public boolean removeChatCustomizer(ChatCustomizer customizer) {
        return delegate.removeChatCustomizer(customizer);
    }

    @Override
    public List<ChatCustomizer> chatCustomizers() {
        return delegate.chatCustomizers();
    }

    @Override
    public void addJsonSchemaCustomizer(JsonSchemaCustomizer customizer) {
        delegate.addJsonSchemaCustomizer(customizer);
    }

    @Override
    public boolean removeJsonSchemaCustomizer(JsonSchemaCustomizer customizer) {
        return delegate.removeJsonSchemaCustomizer(customizer);
    }

    @Override
    public List<JsonSchemaCustomizer> jsonSchemaCustomizers() {
        return delegate.jsonSchemaCustomizers();
    }

    @Override
    public void setDefaultOptions(ChatOptions options) {
        delegate.setDefaultOptions(options);
    }

    @Override
    public @Nullable ChatOptions defaultOptions() {
        return delegate.defaultOptions();
    }

    @Override
    public void addDefaultTool(Tool tool) {
        delegate.addDefaultTool(tool);
    }

    @Override
    public boolean removeDefaultTool(String name) {
        return delegate.removeDefaultTool(name);
    }

    @Override
    public List<Tool> defaultTools() {
        return delegate.defaultTools();
    }

    @Override
    public void addToolProvider(ToolProvider provider) {
        delegate.addToolProvider(provider);
    }

    @Override
    public boolean removeToolProvider(ToolProvider provider) {
        return delegate.removeToolProvider(provider);
    }

    @Override
    public List<ToolProvider> toolProviders() {
        return delegate.toolProviders();
    }

}
