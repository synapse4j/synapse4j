package io.github.synapse4j.spring.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolParameterSpec;

class AutowiredToolMethodSpecCustomizerTest {

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
            CatalogueConfig.class);

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void markedParameterLeavesWire() {
        ToolMethodSpec spec = specOf("byType", Catalogue.class);

        new AutowiredToolMethodSpecCustomizer(context.getBeanFactory()).customize(spec);

        ToolParameterSpec entry = spec.getParameters().get(0);
        assertThat(entry.fromModel()).isFalse();
        assertThat(entry.getValueProvider().get(spec, entry, null)).isSameAs(context.getBean("catalogue"));
    }

    @Test
    void qualifierChoosesItsBean() {
        ToolMethodSpec byAutowired = specOf("byQualifier", Catalogue.class);
        new AutowiredToolMethodSpecCustomizer(context.getBeanFactory()).customize(byAutowired);
        ToolParameterSpec qualified = byAutowired.getParameters().get(0);
        assertThat(qualified.getValueProvider().get(byAutowired, qualified, null)).isSameAs(context.getBean("other"));

        ToolMethodSpec byQualifierAlone = specOf("byQualifierOnly", Catalogue.class);
        new AutowiredToolMethodSpecCustomizer(context.getBeanFactory()).customize(byQualifierAlone);
        ToolParameterSpec alone = byQualifierAlone.getParameters().get(0);
        assertThat(alone.fromModel()).isFalse();
        assertThat(alone.getValueProvider().get(byQualifierAlone, alone, null)).isSameAs(context.getBean("other"));
    }

    @Test
    void valueReadsWhatItNames() {
        ToolMethodSpec spec = specOf("byValue", String.class);

        new AutowiredToolMethodSpecCustomizer(context.getBeanFactory()).customize(spec);

        ToolParameterSpec entry = spec.getParameters().get(0);
        assertThat(entry.fromModel()).isFalse();
        assertThat(entry.getValueProvider().get(spec, entry, null)).isEqualTo("the value");
    }

    @Test
    void unmarkedParameterStaysWithModel() {
        ToolMethodSpec spec = specOf("fromModel", Catalogue.class);

        new AutowiredToolMethodSpecCustomizer(context.getBeanFactory()).customize(spec);

        assertThat(spec.getParameters().get(0).fromModel()).isTrue();
    }

    private static ToolMethodSpec specOf(String name, Class<?> parameter) {
        Method method = method(name, parameter);
        return new ToolMethodSpec(method, null, List.of(new ToolParameterSpec(method.getParameters()[0])));
    }

    private static Method method(String name, Class<?> parameter) {
        try {
            return Fixture.class.getMethod(name, parameter);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** Two candidates for the type, so a qualifier has something to choose between. */
    @Configuration
    static class CatalogueConfig {

        @Bean
        @Primary
        Catalogue catalogue() {
            return new Catalogue();
        }

        @Bean
        @Qualifier("other")
        Catalogue other() {
            return new Catalogue();
        }
    }

    /** The value the container gives; the type the marked parameters ask for. */
    public static class Catalogue {
    }

    /** The methods under test; public members are reached through reflection. */
    public static class Fixture {

        public static String byType(@Autowired Catalogue value) {
            return "ok";
        }

        public static String byQualifier(@Autowired @Qualifier("other") Catalogue value) {
            return "ok";
        }

        public static String byQualifierOnly(@Qualifier("other") Catalogue value) {
            return "ok";
        }

        public static String byValue(@Value("the value") String value) {
            return "ok";
        }

        public static String fromModel(Catalogue value) {
            return "ok";
        }
    }
}
