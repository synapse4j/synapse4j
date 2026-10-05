package io.github.synapse4j.spring.boot.tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.MethodParameter;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;
import io.github.synapse4j.tool.ToolParameterSpec;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The step that fills a parameter the model must not produce — one marked {@link Autowired},
 * {@link Qualifier} or {@link Value} — from the application's beans, resolved the way Spring resolves
 * any injection point, so a qualifier and a property on the parameter both count. A parameter nothing
 * answers for is refused rather than left null.
 */
@RequiredArgsConstructor
public class AutowiredToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    /** Spring's resolution, so a qualifier or a property on the parameter counts. */
    @NonNull
    private final AutowireCapableBeanFactory beanFactory;

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        Method method = spec.getMethod();
        for (int i = 0; i < spec.getParameters().size(); i++) {
            ToolParameterSpec entry = spec.getParameters().get(i);
            if (!fromTheContainer(entry)) {
                continue;
            }
            Object value = beanFactory
                    .resolveDependency(new DependencyDescriptor(new MethodParameter(method, i), true), null);
            entry.setFromModel("false");
            entry.setValueProvider((tool, parameter, context) -> value);
        }
    }

    /**
     * Whether the parameter says the container gives it: marked {@link Autowired}, {@link Qualifier} or {@link Value}.
     */
    private static boolean fromTheContainer(ToolParameterSpec entry) {
        Parameter parameter = entry.getParameter();
        return parameter.isAnnotationPresent(Autowired.class)
                || parameter.isAnnotationPresent(Qualifier.class)
                || parameter.isAnnotationPresent(Value.class);
    }

}
