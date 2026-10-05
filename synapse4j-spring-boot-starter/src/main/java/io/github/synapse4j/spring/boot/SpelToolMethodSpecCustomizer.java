package io.github.synapse4j.spring.boot;

import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.expression.StandardBeanExpressionResolver;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;
import io.github.synapse4j.tool.ToolParameterSpec;

import lombok.NonNull;

/**
 * The step that reads what an annotation wrote as an expression rather than as a value: the text of a
 * tool method — and of each of its parameters — is resolved against the application's configuration
 * and beans before the declaration is built, so a name, a description or a schema can come from a
 * property or a bean instead of being restated on the method.
 *
 * <p>
 * Two spellings resolve, and they are Spring's own: {@code #{...}} is SpEL, evaluated with the
 * application's beans in reach, and {@code ${...}} is a placeholder, read from the environment. They
 * compose — a placeholder inside an expression is filled before the expression runs. Any other text is
 * a literal and is handed back exactly as written, so an annotation that spells a value keeps it, and
 * one that spells a JSON schema keeps the schema.
 *
 * <p>
 * Text that resolves to nothing — an expression that answers {@code null} — is written back blank,
 * which is the same as nobody having supplied it, and the field falls to the default rule it would
 * have had. Blank text is not resolved at all. A result that is not a string is read as its own text,
 * so an expression is expected to answer text — a schema as its JSON document, say.
 *
 * <p>
 * Where it is placed among the {@link ToolMethodSpecCustomizer}s decides what it sees: before the
 * reader's own fallback, so the fallback sees resolved text, and after a step that writes text of its
 * own, so that text is resolved too.
 */
public class SpelToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    /**
     * The application's own factory: where a placeholder is read, and the beans an expression names are
     * reached through.
     */
    private final ConfigurableBeanFactory beanFactory;

    /** What an expression is evaluated in, so a bean it names is resolved through the factory. */
    private final BeanExpressionContext expressionContext;

    /**
     * The factory's own resolver where it has one, so a spelling the application reconfigured is the
     * one that reads an expression; Spring's plain one otherwise.
     */
    private final BeanExpressionResolver resolver;

    /**
     * Builds the step over the factory whose configuration and beans its text is read against.
     *
     * @param beanFactory the application's bean factory; never {@code null}
     */
    public SpelToolMethodSpecCustomizer(@NonNull ConfigurableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        this.expressionContext = new BeanExpressionContext(beanFactory, null);
        BeanExpressionResolver declared = beanFactory.getBeanExpressionResolver();
        this.resolver = declared != null ? declared : new StandardBeanExpressionResolver();
    }

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        spec.setName(resolve(spec.getName()));
        spec.setDescription(resolve(spec.getDescription()));
        spec.setType(resolve(spec.getType()));
        spec.setSchema(resolve(spec.getSchema()));
        spec.setStrict(resolve(spec.getStrict()));
        for (ToolParameterSpec entry : spec.getParameters()) {
            entry.setName(resolve(entry.getName()));
            entry.setDescription(resolve(entry.getDescription()));
            entry.setRequired(resolve(entry.getRequired()));
            entry.setFromModel(resolve(entry.getFromModel()));
            entry.setSchema(resolve(entry.getSchema()));
        }
    }

    private String resolve(String value) {
        if (value.isBlank()) {
            return value;
        }
        String resolved = beanFactory.resolveEmbeddedValue(value);
        if (resolved == null) {
            return "";
        }
        Object evaluated = resolver.evaluate(resolved, expressionContext);
        return evaluated == null ? "" : String.valueOf(evaluated);
    }

}
