package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    @Test
    void proxiedToolBeanKeepsItsTools() {
        runner.withUserConfiguration(ProxyWrappingPostProcessor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClient.class).defaultTools())
                    .extracting(Tool::name)
                    .containsExactly("test_greet");
        });
    }

    @Test
    void duplicateToolNameFailsStartup() {
        runner.withUserConfiguration(RivalToolBean.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("test_greet")
                    .hasMessageContaining("more than once");
        });
    }

    /** A marked bean the processor has to read, so discovery runs and not only the wiring. */
    @Tools(prefix = "test_")
    static class ToolBean {

        @ToolMethod
        public String greet(String who) {
            return who;
        }
    }

    /** A second marked bean declaring a name the first one already declares. */
    @Tools(prefix = "test_")
    static class RivalToolBean {

        @ToolMethod
        public String greet(String who) {
            return who;
        }
    }

    /** Wraps the marked bean the way the container does, so reading it has to see through the proxy. */
    static class ProxyWrappingPostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean instanceof ToolBean ? new ProxyFactory(bean).getProxy() : bean;
        }
    }
}
