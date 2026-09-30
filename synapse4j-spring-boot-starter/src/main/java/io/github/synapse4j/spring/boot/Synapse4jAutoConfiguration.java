package io.github.synapse4j.spring.boot;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import io.github.synapse4j.anthropic.AnthropicChatClient;
import io.github.synapse4j.anthropic.AnthropicConfig;
import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.apache.ApacheHttpClient;
import io.github.synapse4j.http.restclient.RestClientHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.openai.OpenAiCompletionsChatClient;
import io.github.synapse4j.openai.OpenAiConfig;
import io.github.synapse4j.openai.OpenAiResponsesChatClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires a complete synapse4j stack into a Spring Boot application: the Jackson codec, the
 * transport and the chat client that join them — {@code synapse4j.http-client} picks between
 * the Spring and Apache transports, {@code synapse4j.chat-client} between the two OpenAI
 * protocols and Anthropic's — with each family's config bound from its own properties and every
 * client's default options from {@code synapse4j.chat-options.*}.
 *
 * <p>
 * Every bean here backs off the moment the application declares one of the same type — this
 * configuration is the default answer, never the only one, which is the library's own rule about
 * replaceable implementations expressed the way Spring says it. The default transport is built on
 * the auto-configured {@link RestClient.Builder} when Boot publishes one, so interceptors,
 * observations, SSL bundles and {@code spring.http.client.*} settings configured for the rest of
 * the application apply to LLM calls too; with no such bean a plain builder is used instead, so
 * excluding Boot's restclient support degrades the wiring rather than failing it. The codec takes
 * the auto-configured {@link JsonMapper} the same way, and for a sharper reason: the mapper is what
 * decides the names and the shapes of the application's own types, and those are exactly what the
 * schemas sent to the model describe and what tool arguments are bound with.
 *
 * <p>
 * Nothing here reaches for a secret or invents a default the library would not make itself: an
 * API key is whatever {@code synapse4j.openai.api-key} or {@code synapse4j.anthropic.api-key}
 * says (typically a placeholder for an environment variable), and a call made without one fails
 * exactly as it fails without Spring — when it is made, with the library's own message.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "synapse4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Synapse4jProperties.class)
public class Synapse4jAutoConfiguration {

    /**
     * The codec the whole stack serializes through, over the application's own {@code JsonMapper}
     * when one exists and a plain mapper otherwise. The Jackson implementation is this starter's
     * opinion; an application that wants another JSON library declares its own {@link JsonCodec}
     * and this bean never exists.
     *
     * <p>
     * The mapper matters beyond its own configuration: the schemas this codec generates describe the
     * application's types as that mapper names and shapes them, and the same mapper binds the JSON
     * a model sends back. Boot's auto-configured {@code JsonMapper} is the application's mapper in
     * every sense — {@code spring.jackson.*} and every {@code JsonMapperBuilderCustomizer} have
     * already been applied to it — so anything configured for the rest of the application holds for
     * tool arguments and structured output too. A mapper tuned for a web layer travels with its
     * stricter policies; the schema comes from the same mapper, so the two still agree.
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonCodec jsonCodec(ObjectProvider<JsonMapper> mappers) {
        JsonMapper mapper = mappers.getIfAvailable();
        return mapper == null ? new JacksonJsonCodec() : new JacksonJsonCodec(mapper);
    }

    /**
     * The Spring transport — the default, taken when {@code synapse4j.http-client} names no value
     * or names this one — on Boot's auto-configured {@code RestClient.Builder} when one is
     * published and a plain one otherwise. The bound {@code synapse4j.http.*} options become the
     * fallback every call inherits from when it states nothing itself.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "synapse4j", name = "http-client", havingValue = "restclient", matchIfMissing = true)
    public HttpClient httpClient(ObjectProvider<RestClient.Builder> builders, Synapse4jProperties properties) {
        RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
        return new RestClientHttpClient(builder.build(), properties.getHttp());
    }

    /**
     * The Apache transport, the other half of {@code synapse4j.http-client}: the property names
     * one of the two and exactly one of them exists, both backing off before a transport the
     * application declares itself. The stock Apache client stands in as the delegate — its
     * execution chain, pool and all, is HttpClient 5's own — while the bound
     * {@code synapse4j.http.*} options reach it exactly as they reach the Spring transport.
     *
     * <p>
     * The starter keeps Apache HttpClient 5 itself off the application's classpath (the module is
     * a dependency; the library is not — another HTTP stack is the application's choice to make).
     * An application that selects this transport declares httpclient5 itself; without it, only
     * this bean fails, at startup, naming the missing class — the default wiring never loads it.
     *
     * <p>
     * The delegate is a bean of its own, with the context's close for its own: the connection pool
     * it holds outlives any one request, and {@link ApacheHttpClient} never closes what it was
     * handed — so without this, the pool would outlive the context that created it. An application
     * declaring a {@link CloseableHttpClient} of its own supplies the transport instead, pool
     * configuration and all, and owns its disposal as it always did.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CloseableHttpClient.class)
    @ConditionalOnProperty(prefix = "synapse4j", name = "http-client", havingValue = "apache")
    public CloseableHttpClient apacheTransport() {
        return HttpClients.createDefault();
    }

    /**
     * The Apache transport, wired to the delegate above — or to the application's own
     * {@link CloseableHttpClient}, when it declares one.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "synapse4j", name = "http-client", havingValue = "apache")
    public HttpClient apacheHttpClient(CloseableHttpClient transport, Synapse4jProperties properties) {
        return new ApacheHttpClient(transport, properties.getHttp());
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
     * The Anthropic family configuration, bound from {@code synapse4j.anthropic.*} — the same
     * arrangement as {@link #openAiConfig}: the properties object and this bean are the same
     * instance, and an application sourcing it elsewhere declares an {@link AnthropicConfig} bean
     * and wins.
     */
    @Bean
    @ConditionalOnMissingBean
    public AnthropicConfig anthropicConfig(Synapse4jProperties properties) {
        return properties.getAnthropic();
    }

    /**
     * The chat completions client — the default, taken when {@code synapse4j.chat-client} names
     * no value or names this one. Declared as its concrete type so a caller may reach the
     * OpenAI-specific surface, but the missing-bean condition watches the interface: a client of
     * the application's own — another provider, a decorator — is the whole answer and this one
     * never comes into being.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    @ConditionalOnProperty(prefix = "synapse4j", name = "chat-client", havingValue = "completions", matchIfMissing = true)
    public OpenAiCompletionsChatClient openAiCompletionsChatClient(HttpClient http, JsonCodec codec,
            OpenAiConfig config, Synapse4jProperties properties) {
        return withDefaultOptions(new OpenAiCompletionsChatClient(http, codec, config), properties);
    }

    /**
     * The Responses client, the other OpenAI protocol {@code synapse4j.chat-client} can name:
     * the property names one protocol, exactly one of the three beans exists, and all watch the
     * interface the same way, so an application's own {@link ChatClient} wins over any of them.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    @ConditionalOnProperty(prefix = "synapse4j", name = "chat-client", havingValue = "responses")
    public OpenAiResponsesChatClient openAiResponsesChatClient(HttpClient http, JsonCodec codec,
            OpenAiConfig config, Synapse4jProperties properties) {
        return withDefaultOptions(new OpenAiResponsesChatClient(http, codec, config), properties);
    }

    /**
     * The Anthropic Messages client, the third value {@code synapse4j.chat-client} takes — a
     * second provider over the same {@link ChatClient} interface, under its own config bound from
     * {@code synapse4j.anthropic.*}. The same missing-bean condition as the OpenAI pair: an
     * application's own client is the whole answer and this one never comes into being.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    @ConditionalOnProperty(prefix = "synapse4j", name = "chat-client", havingValue = "anthropic")
    public AnthropicChatClient anthropicChatClient(HttpClient http, JsonCodec codec,
            AnthropicConfig config, Synapse4jProperties properties) {
        return withDefaultOptions(new AnthropicChatClient(http, codec, config), properties);
    }

    /**
     * Applies the bound chat options to a freshly built client, so every call it makes inherits the
     * standing model, temperature or response format. Applied here rather than through a constructor
     * because the clients' own constructors are the library's API and take no options.
     */
    private static <T extends ChatClient> T withDefaultOptions(T client, Synapse4jProperties properties) {
        client.setDefaultOptions(properties.getChatOptions().toChatOptions());
        return client;
    }

}
