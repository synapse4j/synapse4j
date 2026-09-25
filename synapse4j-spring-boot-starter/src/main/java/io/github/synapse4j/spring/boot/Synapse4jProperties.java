package io.github.synapse4j.spring.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.openai.OpenAiConfig;

/**
 * The {@code synapse4j.*} settings this starter binds.
 *
 * <p>
 * The two groups are the library's own configuration types held in place rather than restated:
 * {@code synapse4j.openai.*} lands on an {@link OpenAiConfig}, {@code synapse4j.http.*} on
 * {@link HttpOptions}. Spring's binder calls a setter only for a key the environment actually
 * carries, so every property a user does not set keeps the default the library's own instance
 * carries — and there is no second copy of these fields here that could drift from them.
 *
 * <p>
 * Settings that belong to Spring Boot's transport — read and connect timeouts, SSL bundles — are
 * deliberately not restated either. They live under {@code spring.http.client.*} and reach this
 * stack through the auto-configured {@code RestClient} like any other Boot application's HTTP
 * calls.
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

    /** OpenAI family configuration: where the API lives and how the call authenticates. */
    // The marker earns its place: without it the metadata processor stops at the field, because a
    // nested type that comes from a jar is not recursed into on its own — every synapse4j.openai.*
    // key would silently vanish from the configuration metadata.
    @NestedConfigurationProperty
    private final OpenAiConfig openai = new OpenAiConfig();

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
