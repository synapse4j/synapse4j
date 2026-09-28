package io.github.synapse4j.spring.boot;

/**
 * The HTTP transports {@link Synapse4jAutoConfiguration} knows how to wire, as the values
 * {@code synapse4j.http-client} accepts.
 *
 * <p>
 * Closed for the same reason as {@link ChatClientType}: the set is exactly what this
 * configuration can construct — nothing extends it, and an application that wants another
 * transport says so by declaring an {@link io.github.synapse4j.http.HttpClient} bean, which
 * every bean here backs off from. Closed also buys what a free string cannot: the binder
 * refuses an unknown value at startup, naming the property, instead of leaving a container
 * that quietly has no transport to send with.
 */
public enum HttpClientType {

    /** The Spring RestClient transport — the default. */
    RESTCLIENT,

    /** The Apache HttpClient 5 transport. */
    APACHE

}
