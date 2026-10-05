package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.synapse4j.spring.boot.Synapse4jAutoConfiguration;
import io.github.synapse4j.tool.MethodTools;
import io.github.synapse4j.tool.ToolMethod;

class Synapse4jToolsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(Synapse4jAutoConfiguration.class, Synapse4jToolsAutoConfiguration.class))
            .withUserConfiguration(ToolBean.class);

    @Test
    void wiresTheToolSupportAndReadsTheMarkedBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MethodTools.class);
            assertThat(context).hasSingleBean(ToolsProcessor.class);
            assertThat(context).hasSingleBean(ToolsToolMethodSpecCustomizer.class);
            assertThat(context).hasSingleBean(ConfiguredToolMethodSpecCustomizer.class);
            assertThat(context).doesNotHaveBean(SpelToolMethodSpecCustomizer.class);
        });
    }

    @Test
    void turnsOnTheSpelCustomizerFromItsProperty() {
        runner.withPropertyValues("synapse4j.tools.spel=true")
                .run(context -> assertThat(context).hasSingleBean(SpelToolMethodSpecCustomizer.class));
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
