package io.github.synapse4j.spring.boot.tool;

import java.lang.reflect.Method;

import org.springframework.core.annotation.AnnotatedElementUtils;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;

import lombok.NonNull;

/**
 * The step that writes a marked class's {@link Tools#prefix()} in front of every tool name it declares.
 *
 * <p>
 * The prefix is literal text and lands on every tool of the class, whether the annotation named it or it
 * falls back to the method's name; a blank prefix, and a class no {@link Tools} marks, change nothing.
 * It runs before the configuration is looked up, so the name it settles is the one the configuration is
 * found under.
 *
 * <p>
 * The annotation is read through Spring's own support, so the prefix written as {@code @Tools("weather_")}
 * counts as surely as {@code @Tools(prefix = "weather_")}.
 */
public class ToolsToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        Method method = spec.getMethod();
        Tools tools = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), Tools.class);
        if (tools == null || tools.prefix().isBlank()) {
            return;
        }
        String name = spec.getName().isBlank() ? method.getName() : spec.getName();
        spec.setName(tools.prefix() + name);
    }

}
