package io.github.synapse4j.spring.boot;

import io.github.synapse4j.chat.ChatClient;

/**
 * A hook an application declares as a bean to configure the auto-configured {@link ChatClient}, the
 * way Boot's own {@code *Customizer} types work.
 *
 * <p>
 * A client the application declares itself is its own wiring, so these customizers never touch it.
 */
@FunctionalInterface
public interface ChatClientCustomizer {

    /**
     * Configures the client.
     *
     * @param client the auto-configured client; never {@code null}
     */
    void customize(ChatClient client);

}
