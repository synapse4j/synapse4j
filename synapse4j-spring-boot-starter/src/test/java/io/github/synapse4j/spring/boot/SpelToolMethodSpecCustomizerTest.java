package io.github.synapse4j.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolParameterSpec;

class SpelToolMethodSpecCustomizerTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void expressionsResolveAndEverythingElseStays() {
        beanFactory.registerSingleton("greeter", new Greeter());
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("test", Map.<String, Object>of("suffix", "the model")));
        beanFactory.addEmbeddedValueResolver(environment::resolvePlaceholders);

        ToolMethodSpec spec = specOf();
        spec.setName("#{@greeter.greeting()}");
        spec.setDescription("greets ${suffix}");
        spec.setType("plain");
        spec.setSchema("{\"type\":\"object\"}");
        spec.getParameters().get(0).setDescription("#{@greeter.greeting()}");

        new SpelToolMethodSpecCustomizer(beanFactory).customize(spec);

        assertThat(spec.getName()).isEqualTo("hello");
        assertThat(spec.getDescription()).isEqualTo("greets the model");
        assertThat(spec.getType()).isEqualTo("plain");
        assertThat(spec.getSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.getParameters().get(0).getDescription()).isEqualTo("hello");
    }

    @Test
    void anExpressionThatAnswersNothingLeavesTheFieldBlank() {
        ToolMethodSpec spec = specOf();
        spec.setName("#{null}");

        new SpelToolMethodSpecCustomizer(beanFactory).customize(spec);

        assertThat(spec.getName()).isEmpty();
    }

    @Test
    void aSpellingTheApplicationReconfiguredIsHonoured() {
        beanFactory.registerSingleton("greeter", new Greeter());
        StandardBeanExpressionResolver reconfigured = new StandardBeanExpressionResolver();
        reconfigured.setExpressionPrefix("%{");
        beanFactory.setBeanExpressionResolver(reconfigured);

        ToolMethodSpec spec = specOf();
        spec.setName("%{@greeter.greeting()}");

        new SpelToolMethodSpecCustomizer(beanFactory).customize(spec);

        assertThat(spec.getName()).isEqualTo("hello");
    }

    private static ToolMethodSpec specOf() {
        Method method = method("greet");
        return new ToolMethodSpec(method, null, List.of(new ToolParameterSpec(method.getParameters()[0])));
    }

    private static Method method(String name) {
        try {
            return Fixture.class.getMethod(name, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** A bean an expression can name, to stand for whatever an application exposes. */
    public static class Greeter {

        public String greeting() {
            return "hello";
        }
    }

    /** The method under test; public members are reached through reflection. */
    public static class Fixture {

        public static String greet(String name) {
            return name;
        }
    }
}
