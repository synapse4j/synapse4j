package io.github.synapse4j.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.synapse4j.anthropic.AnthropicChatClient;
import io.github.synapse4j.anthropic.AnthropicConfig;
import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.chat.ChatCustomizer;
import io.github.synapse4j.chat.ToolCallingChatClient;
import io.github.synapse4j.data.ChatOptions;
import io.github.synapse4j.data.ChatRequest;
import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.http.DefaultHttpResponse;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.HttpRequest;
import io.github.synapse4j.http.HttpResponse;
import io.github.synapse4j.http.apache.ApacheHttpClient;
import io.github.synapse4j.http.restclient.RestClientHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.openai.OpenAiCompletionsChatClient;
import io.github.synapse4j.openai.OpenAiConfig;
import io.github.synapse4j.openai.OpenAiResponsesChatClient;

class Synapse4jAutoConfigurationTest {

    /** The one registration the whole starter rests on: the classpath file the container reads. */
    private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class));

    @Test
    void wiresTheWholeStackWithNoProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatClient.class);
            // The tool-calling loop is on by default, so the bean is the wrapper around the
            // protocol client rather than the client itself.
            assertThat(context.getBean(ChatClient.class)).isInstanceOf(ToolCallingChatClient.class);
            assertThat(context).hasSingleBean(JsonCodec.class);
            assertThat(context.getBean(JsonCodec.class)).isInstanceOf(JacksonJsonCodec.class);
            assertThat(context).hasSingleBean(HttpClient.class);
            assertThat(context.getBean(HttpClient.class)).isInstanceOf(RestClientHttpClient.class);
            // No property named a base URL, so the starter must leave the library's own default
            // alone — the failure mode is a null guard dropped and a blank URL going out.
            assertThat(context.getBean(Synapse4jProperties.class).getOpenai().getBaseUrl())
                    .isEqualTo(new OpenAiConfig().getBaseUrl());
            // The family configs live in the properties bean, not republished as beans of their
            // own: one instance of each, nothing to drift.
            assertThat(context).doesNotHaveBean(OpenAiConfig.class);
            assertThat(context).doesNotHaveBean(AnthropicConfig.class);
        });
    }

    @Test
    void theProtocolClientIsCompletionsByDefault() {
        // The loop is off so the protocol client itself shows, which is the only way to see which
        // protocol the default picked.
        runner.withPropertyValues("synapse4j.chat.auto-tool-calling=false").run(context -> {
            assertThat(context).hasSingleBean(ChatClient.class);
            assertThat(context.getBean(ChatClient.class)).isInstanceOf(OpenAiCompletionsChatClient.class);
        });
    }

    @Test
    void selectsTheResponsesClientFromItsProperty() {
        // The key and its values are the starter's public contract, like every other key bound
        // here: a rename silently reverts every application that asked for Responses back to
        // the default client, and no other test would notice. The loop is off so the protocol
        // client itself shows.
        runner.withPropertyValues("synapse4j.chat.client=responses", "synapse4j.chat.auto-tool-calling=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context.getBean(ChatClient.class)).isInstanceOf(OpenAiResponsesChatClient.class);
                });
    }

    @Test
    void anUnknownChatClientValueFailsTheContext() {
        // The switch has no case for an unknown value — it would be a missing-bean error far away
        // from the typo. The binding is what refuses it here, at startup, with the property named.
        runner.withPropertyValues("synapse4j.chat.client=bogus")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aRenamedKeyFailsTheContextRatherThanBeingIgnored() {
        // This key moved under synapse4j.chat.* in 0.0.2. An application still carrying the old
        // spelling must not come up with the switch silently dropped.
        runner.withPropertyValues("synapse4j.auto-tool-calling=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void selectsTheAnthropicClientFromItsProperty() {
        // The value is part of the same contract as the key: a rename silently falls every
        // application that asked for Anthropic back to the default client.
        runner.withPropertyValues("synapse4j.chat.client=anthropic", "synapse4j.chat.auto-tool-calling=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context.getBean(ChatClient.class)).isInstanceOf(AnthropicChatClient.class);
                });
    }

    @Test
    void selectsTheApacheTransportFromItsProperty() {
        // The transport key and its values are the starter's public contract for the same
        // reason the chat.client ones are: a rename reverts every application to the Spring
        // transport without a word.
        runner.withPropertyValues("synapse4j.http-client=apache").run(context -> {
            assertThat(context).hasSingleBean(HttpClient.class);
            assertThat(context.getBean(HttpClient.class)).isInstanceOf(ApacheHttpClient.class);
        });
    }

    @Test
    void noApacheTypeSitsOnTheConfigurationEveryApplicationLoads() {
        // Spring introspects a configuration class's bean-method signatures before it evaluates any
        // condition, so a bean method returning or taking an Apache type here fails every
        // application that does not carry httpclient5 — during bean-factory post-processing, with
        // an error that names neither the library nor the property. The Apache beans live in a
        // nested configuration guarded by @ConditionalOnClass instead; this pins them there.
        for (Method method : Synapse4jAutoConfiguration.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName()).doesNotStartWith("org.apache.hc");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat(parameter.getName()).doesNotStartWith("org.apache.hc");
            }
        }
    }

    @Test
    void selectingApacheWithoutItsLibraryFailsNamingTheLibrary() {
        // The starter keeps httpclient5 off the application's classpath, so choosing this transport
        // without declaring the library is a plain misconfiguration. It has to fail with the
        // library named: the selection alone leaves no transport bean, and a bare missing-bean
        // error points at HttpClient, not at what to add.
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(CloseableHttpClient.class))
                .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class))
                .withPropertyValues("synapse4j.http-client=apache")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("httpclient5");
                });
    }

    @Test
    void selectingApacheWithoutItsLibraryStartsWhenTheApplicationWiresItsOwnClient() {
        // The selector names a transport the starter would build. An application that declares its
        // own HttpClient has already wired one by hand, so the selector is inert for it and naming a
        // library it deliberately does not use would be wrong: the context has to start.
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(CloseableHttpClient.class))
                .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class))
                .withPropertyValues("synapse4j.http-client=apache")
                .withBean(HttpClient.class, RestClientHttpClient::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpClient.class);
                });
    }

    @Test
    void aRelaxedTransportSpellingStillSelects() {
        // The binder accepts the spellings Spring binds everywhere else, and the bean that has to
        // exist follows the same value the client reads. A raw string comparison accepted
        // "restclient" but silently left no transport at all for "rest-client" — a value the IDE
        // and Boot's own docs invite.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class))
                .withPropertyValues("synapse4j.http-client=rest-client")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpClient.class);
                    assertThat(context.getBean(HttpClient.class)).isInstanceOf(RestClientHttpClient.class);
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class))
                .withPropertyValues("synapse4j.http-client=APACHE")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpClient.class);
                    assertThat(context.getBean(HttpClient.class)).isInstanceOf(ApacheHttpClient.class);
                });
    }

    @Test
    void theApacheTransportClosesItsPoolWithTheContext() {
        AtomicReference<CloseableHttpClient> transport = new AtomicReference<>();
        runner.withPropertyValues("synapse4j.http-client=apache")
                .run(context -> transport.set(context.getBean(CloseableHttpClient.class)));

        // The pool outlives any one request, and the library's transport never closes the delegate
        // it was handed — so the context's close is the only close it gets. A pool still open here
        // would leave its reactor threads outliving the application that spawned them.
        ClassicHttpRequest request = new BasicClassicHttpRequest("GET", URI.create("http://localhost/"));
        assertThatThrownBy(() -> transport.get().execute(request, response -> response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shut down");
    }

    @Test
    void bindsOpenAiPropertiesOntoTheConfig() {
        runner.withPropertyValues(
                "synapse4j.openai.api-key=sk-test",
                "synapse4j.openai.base-url=https://example.test/v1",
                "synapse4j.openai.organization=org-1",
                "synapse4j.openai.project=proj-1")
                .run(context -> {
                    // The property names are the starter's public contract: a renamed key here
                    // breaks every application's configuration while every test but this one
                    // stays green.
                    OpenAiConfig config = context.getBean(Synapse4jProperties.class).getOpenai();
                    assertThat(config.getApiKey()).isEqualTo("sk-test");
                    assertThat(config.getBaseUrl()).isEqualTo("https://example.test/v1");
                    assertThat(config.getOrganization()).isEqualTo("org-1");
                    assertThat(config.getProject()).isEqualTo("proj-1");
                });
    }

    @Test
    void bindsAnthropicPropertiesOntoTheConfig() {
        runner.withPropertyValues(
                "synapse4j.anthropic.api-key=sk-ant-test",
                "synapse4j.anthropic.base-url=https://example.test/v1",
                "synapse4j.anthropic.anthropic-version=2023-06-01-custom")
                .run(context -> {
                    // Same contract as the OpenAI binding test above: the property names are
                    // what applications configure against.
                    AnthropicConfig config = context.getBean(Synapse4jProperties.class).getAnthropic();
                    assertThat(config.getApiKey()).isEqualTo("sk-ant-test");
                    assertThat(config.getBaseUrl()).isEqualTo("https://example.test/v1");
                    assertThat(config.getAnthropicVersion()).isEqualTo("2023-06-01-custom");
                });
    }

    @Test
    void bindsHttpOptionsProperties() {
        runner.withPropertyValues(
                "synapse4j.http-options.max-frame-bytes=8192",
                "synapse4j.http-options.body-write-mode=buffered")
                .run(context -> {
                    HttpOptions http = context.getBean(Synapse4jProperties.class).getHttpOptions();
                    assertThat(http.getMaxFrameBytes()).isEqualTo(8192);
                    assertThat(http.getBodyWriteMode()).isEqualTo("buffered");
                });
    }

    @Test
    void chatCustomizerBeansRunOnEveryCall() {
        List<String> ran = new ArrayList<>();
        runner.withPropertyValues(
                "synapse4j.openai.api-key=sk-test",
                "synapse4j.chat.options.model=gpt-4o")
                .withBean(HttpClient.class, StubHttpClient::new)
                .withBean(ChatCustomizer.class, () -> new ChatCustomizer() {
                    @Override
                    public void customizeRequest(ChatClient client, ChatRequest request) {
                        ran.add("ran");
                    }
                })
                .run(context -> {
                    context.getBean(ChatClient.class).chat(new ChatRequest().addUserMessage("hi"));
                    assertThat(ran).containsExactly("ran");
                });
    }

    @Test
    void theStandingSystemMessageReachesTheWire() {
        StubHttpClient transport = new StubHttpClient();
        runner.withPropertyValues(
                "synapse4j.openai.api-key=sk-test",
                "synapse4j.chat.options.model=gpt-4o",
                "synapse4j.chat.system-message=You are terse.")
                .withBean(HttpClient.class, () -> transport)
                .run(context -> {
                    // The key is the starter's public contract: a renamed one silently stops framing
                    // every call, and no other test would notice.
                    context.getBean(ChatClient.class).chat(new ChatRequest().addUserMessage("hi"));
                    assertThat(transport.capturedBody).contains("You are terse.");
                });
    }

    @Test
    void aChatClientCustomizerHasTheLastWordOverTheBoundOptions() {
        StubHttpClient transport = new StubHttpClient();
        runner.withPropertyValues(
                "synapse4j.openai.api-key=sk-test",
                "synapse4j.chat.options.model=from-properties")
                .withBean(HttpClient.class, () -> transport)
                .withBean(ChatClientCustomizer.class, () -> client -> {
                    ChatOptions options = new ChatOptions();
                    options.setModel("from-customizer");
                    client.setDefaultOptions(options);
                })
                .run(context -> {
                    context.getBean(ChatClient.class).chat(new ChatRequest().addUserMessage("hi"));
                    assertThat(transport.capturedBody).contains("\"model\":\"from-customizer\"");
                });
    }

    @Test
    void bindsChatOptionsProperties() {
        runner.withPropertyValues(
                "synapse4j.chat.options.model=gpt-4o",
                "synapse4j.chat.options.temperature=0.3",
                "synapse4j.chat.options.reasoning-effort=high",
                "synapse4j.chat.options.response-format.type=json_schema",
                "synapse4j.chat.options.response-format.schema={\"type\":\"object\"}",
                "synapse4j.chat.options.headers.openai-beta=responses=v1",
                "synapse4j.chat.options.extras.service_tier=flex")
                .run(context -> {
                    // The keys are the starter's public contract, like every other key bound here:
                    // a renamed one silently drops the default an application configured.
                    ChatOptions options = context.getBean(Synapse4jProperties.class)
                            .getChat().getOptions().toChatOptions(new JacksonJsonCodec());
                    assertThat(options.getModel()).isEqualTo("gpt-4o");
                    assertThat(options.getTemperature()).isEqualTo(0.3);
                    assertThat(options.getReasoningEffort()).isEqualTo("high");
                    assertThat(options.getResponseFormat().getType()).isEqualTo("json_schema");
                    assertThat(options.getResponseFormat().getSchema().getType()).containsExactly("object");
                    assertThat(options.getHeaders()).containsEntry("openai-beta", "responses=v1");
                    assertThat(options.getExtras().get("service_tier")).isEqualTo("flex");
                });
    }

    @Test
    void theBoundChatOptionsReachTheWire() {
        StubHttpClient transport = new StubHttpClient();
        runner.withPropertyValues(
                "synapse4j.openai.api-key=sk-test",
                "synapse4j.chat.options.model=gpt-4o",
                "synapse4j.chat.options.temperature=0.3")
                .withBean(HttpClient.class, () -> transport)
                .run(context -> {
                    // The default supplies the model the request never states — without it the call
                    // fails its own validation before reaching the transport at all.
                    context.getBean(ChatClient.class).chat(new ChatRequest().addUserMessage("hi"));
                    assertThat(transport.capturedBody).contains("\"model\":\"gpt-4o\"");
                    assertThat(transport.capturedBody).contains("\"temperature\":0.3");
                });
    }

    @Test
    void theApplicationsJacksonConfigurationReachesTheCodec() {
        // Goes through Boot's own Jackson auto-configuration rather than a mapper registered by
        // hand, because the dependency on spring-boot-jackson is what makes the mapper exist in a
        // real application — drop it and this test stops compiling, which is the point.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
                .run(context -> {
                    // The schema describes the application's type the way the application's own
                    // mapper names it; a codec built on a mapper of our own would say userName.
                    JsonSchema schema = context.getBean(JsonCodec.class)
                            .generateEncodeSchema(Payload.class);
                    assertThat(schema.getProperties()).containsOnlyKeys("user_name");
                });
    }

    /** A type the model is asked to fill in, named the way the application names it. */
    record Payload(String userName) {
    }

    @Test
    void applicationBeansWinOverEveryDefault() {
        JsonCodec codec = new JacksonJsonCodec();
        HttpClient http = new RestClientHttpClient();
        ChatClient client = new OpenAiCompletionsChatClient(http, codec, new OpenAiConfig());
        runner.withBean(JsonCodec.class, () -> codec)
                .withBean(HttpClient.class, () -> http)
                .withBean(ChatClient.class, () -> client)
                .run(context -> {
                    assertThat(context).hasSingleBean(JsonCodec.class);
                    assertThat(context.getBean(JsonCodec.class)).isSameAs(codec);
                    assertThat(context).hasSingleBean(HttpClient.class);
                    assertThat(context.getBean(HttpClient.class)).isSameAs(http);
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context.getBean(ChatClient.class)).isSameAs(client);
                });
    }

    @Test
    void disabledSwitchesTheWholeStarterOff() {
        runner.withPropertyValues("synapse4j.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ChatClient.class);
            assertThat(context).doesNotHaveBean(JsonCodec.class);
            assertThat(context).doesNotHaveBean(HttpClient.class);
        });
    }

    @Test
    void listedInAutoConfigurationImports() throws IOException {
        // Every other test here registers the class directly and would stay green if the imports
        // file went missing or misnamed — the file alone is what makes a real application see it.
        String expected = Synapse4jAutoConfiguration.class.getName();
        boolean found = false;
        for (URL url : Collections.list(
                getClass().getClassLoader().getResources(IMPORTS))) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                if (reader.lines().anyMatch(expected::equals)) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).as("listed in " + IMPORTS).isTrue();
    }

    /** Captures the outgoing request body and replays a canned completion. */
    static class StubHttpClient implements HttpClient {

        String capturedBody;

        @Override
        public HttpResponse send(HttpRequest request) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try {
                request.getBody().writeTo(body);
            } catch (IOException e) {
                throw new SynapseException("the request body could not be written", e);
            }
            capturedBody = body.toString(StandardCharsets.UTF_8);
            DefaultHttpResponse canned = new DefaultHttpResponse();
            canned.setStatusCode(200);
            canned.setBody(new ByteArrayInputStream(
                    ("{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
                            + "{\"role\":\"assistant\",\"content\":\"hi\"}}]}")
                            .getBytes(StandardCharsets.UTF_8)));
            canned.setOptions(HttpOptions.effective(request.getOptions(), HttpOptions.defaults()));
            return canned;
        }
    }

}
