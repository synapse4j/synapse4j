package io.github.synapse4j.spring.boot;

import java.util.Objects;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.client.RestClient;

import io.github.synapse4j.anthropic.AnthropicChatClient;
import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.chat.ChatCustomizer;
import io.github.synapse4j.chat.DefaultSystemMessageCustomizer;
import io.github.synapse4j.chat.ToolCallingChatClient;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.apache.ApacheHttpClient;
import io.github.synapse4j.http.restclient.RestClientHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.openai.OpenAiCompletionsChatClient;
import io.github.synapse4j.openai.OpenAiResponsesChatClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires a complete synapse4j stack into a Spring Boot application: the Jackson codec, the
 * transport and the chat client that join them — {@code synapse4j.http-client} picks between
 * the Spring and Apache transports, {@code synapse4j.chat.client} between the two OpenAI
 * protocols and Anthropic's — with each family's config bound from its own properties and every
 * client's default options from {@code synapse4j.chat.options.*}.
 *
 * <p>
 * The family configs are taken straight from the properties bean rather than republished as beans
 * of their own — there is one instance of each and nothing to drift, and injecting
 * {@link Synapse4jProperties} is how an application reaches one.
 *
 * <p>
 * A built chat client is brought into shape before it is shared: it is wrapped in the tool-calling
 * loop unless {@code synapse4j.chat.auto-tool-calling} is off, the bound chat options become its
 * defaults, every {@link ChatCustomizer} bean joins its per-call hooks, and every
 * {@link ChatClientCustomizer} bean then gets the last word.
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
     * The order the starter's own {@link ChatCustomizer}s are given, so an application can place one
     * of its own before or after them deliberately — a higher order runs later.
     */
    private static final int ORDER = 0;

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
     * published and a plain one otherwise. The bound {@code synapse4j.http-options.*} options become
     * the fallback every call inherits from when it states nothing itself.
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnRestClientTransport.class)
    public HttpClient httpClient(ObjectProvider<RestClient.Builder> builders, Synapse4jProperties properties) {
        RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
        return new RestClientHttpClient(builder.build(), properties.getHttpOptions());
    }

    /**
     * The Apache transport, the other half of {@code synapse4j.http-client}, in a configuration of
     * its own so that its signatures are never introspected on a classpath without Apache
     * HttpClient 5. Spring reads {@code @ConditionalOnClass} from a configuration class's bytecode
     * before it loads the class, so when the library is absent this class — and the
     * {@code CloseableHttpClient} its bean methods name — is never reached, and the rest of the
     * wiring is unaffected. Keeping the two Apache beans at the top level instead made Spring
     * introspect their signatures for every application: on a classpath without the library the
     * context failed during bean-factory post-processing, naming an unrelated bean and neither the
     * library nor the property.
     *
     * <p>
     * The starter deliberately keeps Apache HttpClient 5 off the application's classpath (the module
     * is a dependency; the library is not — another HTTP stack is the application's choice to make),
     * so an application that selects this transport declares httpclient5 itself. The stock Apache
     * client stands in as the delegate — its execution chain, pool and all, is HttpClient 5's own —
     * while the bound {@code synapse4j.http-options.*} options reach it exactly as they reach the
     * Spring transport. The delegate is a bean of its own, with the context's close for its own: the
     * connection pool it holds outlives any one request, and {@link ApacheHttpClient} never closes
     * what it was handed — so without this, the pool would outlive the context that created it. An
     * application declaring a {@link CloseableHttpClient} of its own supplies the transport instead,
     * pool configuration and all, and owns its disposal as it always did; one declaring its own
     * {@link HttpClient} is served by neither bean here.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(CloseableHttpClient.class)
    @Conditional(OnApacheTransport.class)
    static class ApacheTransportConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean({ CloseableHttpClient.class, HttpClient.class })
        CloseableHttpClient apacheTransport() {
            return HttpClients.createDefault();
        }

        @Bean
        @ConditionalOnMissingBean
        HttpClient apacheHttpClient(CloseableHttpClient transport, Synapse4jProperties properties) {
            return new ApacheHttpClient(transport, properties.getHttpOptions());
        }
    }

    /**
     * Fails the context, clearly, when {@code synapse4j.http-client=apache} is selected but Apache
     * HttpClient 5 is not on the classpath. Without this the selection would fail as a missing
     * {@link HttpClient} bean — an error that names neither the property nor the library — so the
     * message here says exactly what to add.
     *
     * <p>
     * It fires only when the starter would have to build the transport: an application that declares
     * its own {@link HttpClient} has already wired one by hand, so the selector is inert for it and
     * naming a library it deliberately does not use would be wrong.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
    @Conditional(OnApacheTransport.class)
    static class ApacheTransportMissingConfiguration {

        @Bean
        @ConditionalOnMissingBean(HttpClient.class)
        HttpClient apacheTransportMissing() {
            throw new IllegalStateException(
                    "synapse4j.http-client=apache needs Apache HttpClient 5 on the classpath: declare a "
                            + "dependency on org.apache.httpcomponents.client5:httpclient5");
        }
    }

    /**
     * The transport {@code synapse4j.http-client} selects, read through the same binder every other
     * {@code synapse4j.*} key uses: a raw string comparison would accept one spelling and refuse
     * another the user is entitled to write — {@code rest-client} binds the enum as surely as
     * {@code restclient}, and the bean that must exist has to follow the value the client reads.
     */
    private static HttpClientType selectedTransport(ConditionContext context) {
        return Binder.get(context.getEnvironment())
                .bind("synapse4j.http-client", HttpClientType.class)
                .orElse(HttpClientType.RESTCLIENT);
    }

    /** Matches when {@code synapse4j.http-client} binds to the Spring transport, the default. */
    static class OnRestClientTransport implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selectedTransport(context) == HttpClientType.RESTCLIENT;
        }
    }

    /** Matches when {@code synapse4j.http-client} binds to the Apache transport. */
    static class OnApacheTransport implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return selectedTransport(context) == HttpClientType.APACHE;
        }
    }

    /**
     * The chat client the application talks to: the protocol {@code synapse4j.chat.client} names —
     * the default completions one, Responses, or Anthropic's — wrapped in the tool-calling loop
     * unless {@code synapse4j.chat.auto-tool-calling} is off.
     *
     * <p>
     * Declared as the interface, not the protocol's own type, because the loop wrapper is not one of
     * them. A caller that needs the provider's surface — {@code setConfig}, say — declares its own
     * {@link ChatClient} bean, and this whole default backs off.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatClient chatClient(HttpClient http, JsonCodec codec, Synapse4jProperties properties,
            ObjectProvider<ChatCustomizer> chatCustomizers,
            ObjectProvider<ChatClientCustomizer> clientCustomizers) {
        ChatProperties chat = properties.getChat();
        ChatClient client = switch (chat.getClient()) {
            case COMPLETIONS -> new OpenAiCompletionsChatClient(http, codec, properties.getOpenai());
            case RESPONSES -> new OpenAiResponsesChatClient(http, codec, properties.getOpenai());
            case ANTHROPIC -> new AnthropicChatClient(http, codec, properties.getAnthropic());
        };
        if (chat.isAutoToolCalling()) {
            client = new ToolCallingChatClient(client);
        }
        return assemble(client, properties, chatCustomizers, clientCustomizers);
    }

    /**
     * The standing system message {@code synapse4j.chat.system-message} names, as a
     * {@link ChatCustomizer} bean: it fills a system message into each call that carries none, so
     * the application's framing need not be restated per request. Declared only when the property is
     * set — an application that names none wires none, and one that wants a different rule declares
     * a customizer of its own, which runs beside this one and has the last word on any request it
     * frames.
     *
     * <p>
     * Ordered at {@link #ORDER}, so an application can place a customizer of its own before or after
     * this one deliberately — a higher order runs later and so has the last word.
     *
     * <p>
     * The condition is what guarantees the value is set; the narrowing makes the nullable getter
     * meet the constructor's non-null contract.
     */
    @Bean
    @Order(ORDER)
    @ConditionalOnProperty(prefix = "synapse4j.chat", name = "system-message")
    public ChatCustomizer systemMessageCustomizer(Synapse4jProperties properties) {
        return new DefaultSystemMessageCustomizer(
                Objects.requireNonNull(properties.getChat().getSystemMessage()));
    }

    /**
     * Brings a freshly built client into shape before it is shared: the bound {@code
     * synapse4j.chat.options.*} become its default options, every {@link ChatCustomizer} bean joins
     * its per-call hooks, and every {@link ChatClientCustomizer} bean then gets the last word.
     * Applied here rather than through a constructor because the clients' own constructors are the
     * library's API and take no options.
     */
    private static ChatClient assemble(ChatClient client, Synapse4jProperties properties,
            ObjectProvider<ChatCustomizer> chatCustomizers,
            ObjectProvider<ChatClientCustomizer> clientCustomizers) {
        client.setDefaultOptions(properties.getChat().getOptions().toChatOptions());
        chatCustomizers.orderedStream().forEach(client::addChatCustomizer);
        clientCustomizers.orderedStream().forEach(customizer -> customizer.customize(client));
        return client;
    }

}
