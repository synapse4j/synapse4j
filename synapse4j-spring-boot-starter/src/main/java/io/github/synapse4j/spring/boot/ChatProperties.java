package io.github.synapse4j.spring.boot;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The {@code synapse4j.chat.*} settings: which chat client the auto-configuration builds, whether it
 * runs the model's tool calls, the standing system message, and the defaults every call inherits.
 *
 * <p>
 * Grouped under one key rather than left flat at the root because they are what only a chat call
 * has. The family settings ({@code synapse4j.openai.*}, {@code synapse4j.anthropic.*}) and the
 * transport settings ({@code synapse4j.http-*}) stay at the root, where they are shared: another
 * capability — an embeddings client, say — reuses those and gets a group of its own beside this one,
 * instead of adding keys that a reader cannot tell apart from the chat ones.
 */
@Getter
@Setter
public class ChatProperties {

    /**
     * Which chat client the auto-configuration builds: {@link ChatClientType#COMPLETIONS} (the
     * default), {@link ChatClientType#RESPONSES}, or {@link ChatClientType#ANTHROPIC}. The client
     * bean reads it to pick the protocol, and the binding is what refuses a value this starter does
     * not wire, naming the property at startup.
     */
    private ChatClientType client = ChatClientType.COMPLETIONS;

    /**
     * Whether the auto-configured chat client runs the model's tool-call rounds itself: it executes
     * the tools a request carries and sends their results back until the model answers without
     * calling one. Turned off, the raw calls reach the caller, which runs them itself.
     */
    private boolean autoToolCalling = true;

    /**
     * A system message every auto-configured call runs under: instructions the model answers under,
     * given to each request that carries no system message of its own. Absent by default, so no
     * standing system message is sent until one is named here.
     */
    private @Nullable String systemMessage;

    /**
     * The defaults every auto-configured call inherits from, filling in what each request leaves
     * unstated.
     */
    @NestedConfigurationProperty
    private final ChatOptionsProperties options = new ChatOptionsProperties();

}
