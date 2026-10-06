package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.synapse4j.chat.ChatClient;
import io.github.synapse4j.spring.boot.Synapse4jAutoConfiguration;
import io.github.synapse4j.tool.MethodTools;
import io.github.synapse4j.tool.Tool;
import io.github.synapse4j.tool.ToolMethod;

class Synapse4jToolsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(Synapse4jAutoConfiguration.class, Synapse4jToolsAutoConfiguration.class))
            .withUserConfiguration(ToolBean.class);

    @Test
    void wiresToolSupportMarkedBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MethodTools.class);
            assertThat(context).hasSingleBean(ToolsProcessor.class);
            assertThat(context).hasSingleBean(ToolsToolMethodSpecCustomizer.class);
            assertThat(context).hasSingleBean(ConfiguredToolMethodSpecCustomizer.class);
            assertThat(context).hasSingleBean(AutowiredToolMethodSpecCustomizer.class);
            assertThat(context).doesNotHaveBean(SpelToolMethodSpecCustomizer.class);
        });
    }

    @Test
    void spelCustomizerToggledByProperty() {
        runner.withPropertyValues("synapse4j.tools.spel=true")
                .run(context -> assertThat(context).hasSingleBean(SpelToolMethodSpecCustomizer.class));
    }

    @Test
    void markedToolsLandOnClient() {
        runner.run(context -> assertThat(context.getBean(ChatClient.class).defaultTools())
                .extracting(Tool::name)
                .containsExactly("test_greet"));
    }

    /** A marked bean the processor has to read, so discovery runs and not only the wiring. */
    @Tools(prefix = "test_")
    static class ToolBean {

        @ToolMethod
        public String greet(String who) {
            return who;
        }
    }
}
