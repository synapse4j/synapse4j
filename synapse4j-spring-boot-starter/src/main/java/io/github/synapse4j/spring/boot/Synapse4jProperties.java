package io.github.synapse4j.spring.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import io.github.synapse4j.anthropic.AnthropicConfig;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.openai.OpenAiConfig;

/**
 * The {@code synapse4j.*} settings this starter binds.
 *
 * <p>
 * The groups are the library's own configuration types held in place rather than restated:
 * {@code synapse4j.openai.*} lands on an {@link OpenAiConfig}, {@code synapse4j.anthropic.*} on
 * an {@link AnthropicConfig}, {@code synapse4j.http.*} on {@link HttpOptions}. Spring's binder
 * calls a setter only for a key the environment actually
 * carries, so every property a user does not set keeps the default the library's own instance
 * carries — and there is no second copy of these fields here that could drift from them.
 *
 * <p>
 * {@code synapse4j.chat-options.*} is the one exception, held as a {@link ChatOptionsProperties}
 * rather than a {@link ChatOptions}: the library type cannot be bound, because its nested response
 * format and HTTP options are types from another jar the metadata processor will not recurse into,
 * and its {@link ProviderExtras} bag has no shape Spring can write into. The mirror restates only
 * the bindable fields, and {@link ChatOptionsProperties#toChatOptions()} rebuilds the library type.
 *
 * <p>
 * Settings that belong to Spring Boot's transport — read and connect timeouts, SSL bundles — are
 * deliberately not restated either. They live under {@code spring.http.client.*} and reach a
 * {@code RestClient}-backed transport like any other Boot application's HTTP calls.
 */
@Data
@ConfigurationProperties(prefix = "synapse4j")
public class Synapse4jProperties {

    /**
     * Whether the synapse4j auto-configuration runs.
     *
     * <p>
     * Read as a Spring condition rather than from this instance — a condition evaluates before any
     * bean of this type exists — and declared here so the switch appears in the generated
     * configuration metadata where an IDE can surface it.
     */
    private boolean enabled = true;

    /**
     * Which chat client the auto-configuration builds: {@link ChatClientType#COMPLETIONS} (the
     * default), {@link ChatClientType#RESPONSES}, or {@link ChatClientType#ANTHROPIC}.
     *
     * <p>
     * Read as a Spring condition rather than from this instance — a condition evaluates before any
     * bean of this type exists — and declared here so the selector appears in the generated
     * configuration metadata where an IDE can surface it. The binding still matters: it is what
     * refuses a value this starter does not wire, naming the property at startup, where the
     * conditions alone would answer an unknown value by building nothing.
     */
    private ChatClientType chatClient = ChatClientType.COMPLETIONS;

    /**
     * Which HTTP transport the auto-configuration builds: {@link HttpClientType#RESTCLIENT} (the
     * default) or {@link HttpClientType#APACHE}.
     *
     * <p>
     * Read as a Spring condition rather than from this instance, for the same reason as
     * {@link #chatClient} — and the binding refuses what the conditions would answer by building
     * nothing.
     */
    private HttpClientType httpClient = HttpClientType.RESTCLIENT;

    /** OpenAI family configuration: where the API lives and how the call authenticates. */
    // The marker earns its place: without it the metadata processor stops at the field, because a
    // nested type that comes from a jar is not recursed into on its own — every synapse4j.openai.*
    // key would silently vanish from the configuration metadata.
    @NestedConfigurationProperty
    private final OpenAiConfig openai = new OpenAiConfig();

    /** Anthropic family configuration: where the Messages API lives and how the call authenticates. */
    // Same reason for the marker as on openai: without it every synapse4j.anthropic.* key would
    // silently vanish from the configuration metadata.
    @NestedConfigurationProperty
    private final AnthropicConfig anthropic = new AnthropicConfig();

    /**
     * The defaults every auto-configured call inherits from, filling in what each request leaves
     * unstated.
     */
    @NestedConfigurationProperty
    private final ChatOptionsProperties chatOptions = new ChatOptionsProperties();

    /**
     * The transport's fallback options, for what a call does not state itself.
     *
     * <p>
     * {@code synapse4j.http.response-timeout} binds like any other {@link HttpOptions} member, but
     * the {@code RestClient} transport has no per-request timeout to honour it: it reports the
     * setting once and carries on, and the timeout that works is the request factory's —
     * {@code spring.http.client.read-timeout}.
     */
    @NestedConfigurationProperty
    private final HttpOptions http = new HttpOptions();

}
