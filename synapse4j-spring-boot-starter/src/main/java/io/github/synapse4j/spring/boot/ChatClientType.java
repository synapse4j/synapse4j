package io.github.synapse4j.spring.boot;

/**
 * The chat clients {@link Synapse4jAutoConfiguration} knows how to wire, as the values
 * {@code synapse4j.chat.client} accepts.
 *
 * <p>
 * A closed type is right here in a way it would not be in the library itself: the set is exactly
 * what this configuration can construct — nothing extends it, and an application that wants
 * another client says so by declaring a {@code ChatClient} bean, which every bean here backs off
 * from. Closed also buys what a free string cannot: the binder refuses an unknown value at startup,
 * naming the property, instead of leaving a container that quietly has no client to inject.
 */
public enum ChatClientType {

    /** The chat completions protocol. */
    COMPLETIONS,

    /** The Responses protocol, where a call can continue from a response the server kept. */
    RESPONSES,

    /** The Anthropic Messages protocol. */
    ANTHROPIC

}
