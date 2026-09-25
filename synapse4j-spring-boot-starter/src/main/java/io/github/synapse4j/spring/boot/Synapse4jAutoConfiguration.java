package io.github.synapse4j.spring.boot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.restclient.RestClientHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.openai.OpenAiChatClient;
import io.github.synapse4j.openai.OpenAiConfig;

/**
 * Wires a complete synapse4j stack into a Spring Boot application: the Jackson codec, the
 * {@code RestClient} transport, the OpenAI family config and the chat client that joins them.
 *
 * <p>
 * Every bean here backs off the moment the application declares one of the same type — this
 * configuration is the default answer, never the only one, which is the library's own rule about
 * replaceable implementations expressed the way Spring says it. The transport is built on the
 * auto-configured {@link RestClient.Builder} when Boot publishes one, so interceptors,
 * observations, SSL bundles and {@code spring.http.client.*} settings configured for the rest of
 * the application apply to LLM calls too; with no such bean a plain builder is used instead, so
 * excluding Boot's restclient support degrades the wiring rather than failing it.
 *
 * <p>
 * Nothing here reaches for a secret or invents a default the library would not make itself: the
 * API key is whatever {@code synapse4j.openai.api-key} says (typically a placeholder for an
 * environment variable), and a call made without one fails exactly as it fails without Spring —
 * when it is made, with the library's own message.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "synapse4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Synapse4jProperties.class)
public class Synapse4jAutoConfiguration {

    /**
     * The codec the whole stack serializes through. The Jackson implementation is this starter's
     * opinion; an application that wants another JSON library declares its own {@link JsonCodec}
     * and this bean never exists.
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonCodec jsonCodec() {
        return new JacksonJsonCodec();
    }

    /**
     * The transport, on Boot's auto-configured {@code RestClient.Builder} when one is published
     * and a plain one otherwise. The bound {@code synapse4j.http.*} options become the fallback
     * every call inherits from when it states nothing itself.
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpClient httpClient(ObjectProvider<RestClient.Builder> builders, Synapse4jProperties properties) {
        RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
        return new RestClientHttpClient(builder.build(), properties.getHttp());
    }

    /**
     * The OpenAI family configuration, bound from {@code synapse4j.openai.*} onto the library's
     * own type — the properties object and this bean are the same instance, so a default the
     * binder never touched is exactly what the client sends. An application wanting to source the
     * config elsewhere — its own properties, a vault — declares an {@link OpenAiConfig} bean and
     * wins.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenAiConfig openAiConfig(Synapse4jProperties properties) {
        return properties.getOpenai();
    }

    /**
     * The chat client the application injects as {@link ChatClient}. Declared as its concrete type
     * so a caller may reach the OpenAI-specific surface, but the missing-bean condition watches
     * the interface: a client of the application's own — another provider, a decorator — is the
     * whole answer and this one never comes into being.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public OpenAiChatClient openAiChatClient(HttpClient http, JsonCodec codec, OpenAiConfig config) {
        OpenAiChatClient client = new OpenAiChatClient(http, codec);
        client.setConfig(config);
        return client;
    }

}
