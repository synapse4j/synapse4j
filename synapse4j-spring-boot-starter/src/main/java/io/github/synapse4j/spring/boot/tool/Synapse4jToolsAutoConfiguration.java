package io.github.synapse4j.spring.boot.tool;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.spring.boot.Synapse4jAutoConfiguration;
import io.github.synapse4j.tool.MethodTools;
import io.github.synapse4j.tool.SpecToolFactory;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;

/**
 * Wires the tool support into a Spring application: the {@link MethodTools} that reads an annotated
 * method, the customizers it runs, and the {@link ToolsProcessor} that collects the beans marked
 * {@link Tools} onto the chat clients.
 *
 * <p>
 * The customizers this starter ships are beans of their own, ordered so that what a step reads is what
 * the step before it wrote: the class's prefix is written first, the application's configuration applied
 * next, and expressions resolved last — so a value written in configuration may itself be an expression.
 * An application extends the chain by declaring a {@link ToolMethodSpecCustomizer} of its own, ordered
 * to land where it wants; a {@link SpecToolFactory} of its own is what the reader builds tools with.
 *
 * <p>
 * Every bean backs off the moment the application declares one of the same type, so any piece — the
 * reader, a customizer, the processor — can be replaced or left out.
 *
 * <p>
 * It runs after {@link Synapse4jAutoConfiguration}, whose {@link JsonCodec} the reader is built over,
 * and only where {@code synapse4j.enabled} is not turned off.
 */
@AutoConfiguration(after = Synapse4jAutoConfiguration.class)
@ConditionalOnProperty(prefix = "synapse4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolsProperties.class)
public class Synapse4jToolsAutoConfiguration {

    /** The step that writes a marked class's prefix in front of its tools' names, before the rest. */
    @Bean
    @Order(0)
    @ConditionalOnMissingBean
    ToolsToolMethodSpecCustomizer toolsToolMethodSpecCustomizer() {
        return new ToolsToolMethodSpecCustomizer();
    }

    /** The step that applies {@code synapse4j.tools.*}, over what the annotations settled. */
    @Bean
    @Order(100)
    @ConditionalOnMissingBean
    ConfiguredToolMethodSpecCustomizer configuredToolMethodSpecCustomizer(ToolsProperties tools) {
        return new ConfiguredToolMethodSpecCustomizer(tools);
    }

    /**
     * The step that resolves an annotation's text as an expression, last so that a value the
     * configuration wrote may itself be one. It is off by default, because resolution reaches the
     * application's beans; {@code synapse4j.tools.spel} turns it on.
     */
    @Bean
    @Order(200)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "synapse4j.tools", name = "spel", havingValue = "true")
    SpelToolMethodSpecCustomizer spelToolMethodSpecCustomizer(ConfigurableBeanFactory beanFactory) {
        return new SpelToolMethodSpecCustomizer(beanFactory);
    }

    /**
     * The reader every tool is built with: over the application's codec, running every
     * {@link ToolMethodSpecCustomizer} bean in its order — the ones above and any the application adds —
     * and building tools through the application's {@link SpecToolFactory} where it declared one.
     */
    @Bean
    @ConditionalOnMissingBean
    MethodTools methodTools(JsonCodec codec, ObjectProvider<ToolMethodSpecCustomizer> customizers,
            ObjectProvider<SpecToolFactory> specToolFactory) {
        MethodTools tools = new MethodTools(codec);
        customizers.orderedStream().forEach(tools::addCustomizer);
        SpecToolFactory factory = specToolFactory.getIfAvailable();
        if (factory != null) {
            tools.specToolFactory(factory);
        }
        return tools;
    }

    /** The step that collects the beans marked {@link Tools} onto the chat clients at the end of startup. */
    @Bean
    @ConditionalOnMissingBean
    ToolsProcessor toolsProcessor(MethodTools methodTools, ListableBeanFactory beanFactory) {
        return new ToolsProcessor(methodTools, beanFactory);
    }

}
