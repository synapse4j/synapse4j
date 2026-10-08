package io.github.synapse4j.spring.boot.tool;

import java.lang.reflect.Method;

import org.springframework.core.annotation.AnnotatedElementUtils;

import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;
import lombok.NonNull;

/**
 * The step that writes a marked class's {@link Tools#prefix()} in front of every tool name read from it.
 *
 * <p>
 * The class is the one the reader was asked about — {@link ToolMethodSpec#getOwner()} — so a tool read
 * from a marked class carries its prefix whether that class declares the method itself or only inherits
 * it, and a subclass marked apart from its superclass carries the subclass's. The annotation is looked
 * for on that class and then up its ancestors. The prefix is literal text and lands on every tool of the
 * class, whether an annotation named it or it falls back to the method's name; a blank prefix, and a
 * class neither it nor an ancestor of which is marked, change nothing.
 *
 * <p>
 * The annotation is read through Spring's own support, so the prefix written as {@code @Tools("weather_")}
 * counts as surely as {@code @Tools(prefix = "weather_")}.
 */
public class ToolsToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        Method method = spec.getMethod();
        Tools tools = AnnotatedElementUtils.findMergedAnnotation(spec.getOwner(), Tools.class);
        if (tools == null || tools.prefix().isBlank()) {
            return;
        }
        String name = spec.getName().isBlank() ? method.getName() : spec.getName();
        spec.setName(tools.prefix() + name);
    }

}
