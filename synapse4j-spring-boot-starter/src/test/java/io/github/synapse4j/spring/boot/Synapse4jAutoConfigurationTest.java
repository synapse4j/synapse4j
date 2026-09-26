package io.github.synapse4j.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.http.HttpClient;
import io.github.synapse4j.http.HttpOptions;
import io.github.synapse4j.http.restclient.RestClientHttpClient;
import io.github.synapse4j.jackson.JacksonJsonCodec;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.openai.OpenAiChatClient;
import io.github.synapse4j.openai.OpenAiConfig;

class Synapse4jAutoConfigurationTest {

    /** The one registration the whole starter rests on: the classpath file the container reads. */
    private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Synapse4jAutoConfiguration.class));

    @Test
    void wiresTheWholeStackWithNoProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatClient.class);
            assertThat(context.getBean(ChatClient.class)).isInstanceOf(OpenAiChatClient.class);
            assertThat(context).hasSingleBean(JsonCodec.class);
            assertThat(context.getBean(JsonCodec.class)).isInstanceOf(JacksonJsonCodec.class);
            assertThat(context).hasSingleBean(HttpClient.class);
            assertThat(context.getBean(HttpClient.class)).isInstanceOf(RestClientHttpClient.class);
            // No property named a base URL, so the starter must leave the library's own default
            // alone — the failure mode is a null guard dropped and a blank URL going out.
            assertThat(context.getBean(OpenAiConfig.class).getBaseUrl())
                    .isEqualTo(new OpenAiConfig().getBaseUrl());
        });
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
                    OpenAiConfig config = context.getBean(OpenAiConfig.class);
                    assertThat(config.getApiKey()).isEqualTo("sk-test");
                    assertThat(config.getBaseUrl()).isEqualTo("https://example.test/v1");
                    assertThat(config.getOrganization()).isEqualTo("org-1");
                    assertThat(config.getProject()).isEqualTo("proj-1");
                });
    }

    @Test
    void bindsHttpProperties() {
        runner.withPropertyValues(
                "synapse4j.http.max-frame-bytes=8192",
                "synapse4j.http.body-write-mode=buffered")
                .run(context -> {
                    HttpOptions http = context.getBean(Synapse4jProperties.class).getHttp();
                    assertThat(http.getMaxFrameBytes()).isEqualTo(8192);
                    assertThat(http.getBodyWriteMode()).isEqualTo("buffered");
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
        ChatClient client = new OpenAiChatClient(http, codec);
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

}
