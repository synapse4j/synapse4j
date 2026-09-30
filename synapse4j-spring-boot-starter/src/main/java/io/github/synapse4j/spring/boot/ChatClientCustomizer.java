package io.github.synapse4j.spring.boot;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.chat.ChatCustomizer;

/**
 * A hook an application declares as a bean to configure the auto-configured {@link ChatClient}, the
 * way Boot's own {@code *Customizer} types work.
 *
 * <p>
 * Every such bean is applied in order, after the {@code synapse4j.chat-options.*} defaults, so code
 * has the last word on what a client sends: a customizer can replace the default options, register
 * standing tools or tool providers, or add a {@link ChatCustomizer} whose hooks run on every call.
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
