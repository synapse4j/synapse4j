package io.github.synapse4j.spring.boot.tool;

import io.github.synapse4j.data.ProviderExtras;
import io.github.synapse4j.tool.ToolMethodSpec;
import io.github.synapse4j.tool.ToolMethodSpecCustomizer;
import io.github.synapse4j.tool.ToolParameterSpec;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The step that applies the application's tool configuration to a method as it is resolved: what
 * {@code synapse4j.tools.*} says about this one tool — and the one thing it says about every tool — is
 * written into the spec, over what the annotations supplied.
 *
 * <p>
 * A tool is found under the name it carries when this step runs, and one carrying none takes the
 * method's own name. A name the configuration writes does not move the entry it came from.
 *
 * <p>
 * Every value is text and is written the way an annotation would carry it: a schema is a JSON document,
 * and the extras are merged under their raw keys. A value left out leaves the annotation's own standing;
 * one written, an empty one included, takes its place. {@link ToolsProperties#getStrict()} is the
 * fallback for a tool that states none of its own.
 */
@RequiredArgsConstructor
public class ConfiguredToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    /** The configuration to apply; never {@code null}. */
    @NonNull
    private final ToolsProperties tools;

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        String name = nameBeforeConfiguration(spec);
        ToolMethodProperties configured = tools.getMethods().get(name);
        if (configured != null) {
            apply(configured, spec);
        }
        if (spec.getStrict().isBlank() && tools.getStrict() != null) {
            spec.setStrict(tools.getStrict());
        }
        if (spec.getName().isBlank()) {
            spec.setName(name);
        }
    }

    /** The name the tool is found under: what it carries now, or the method's own name when it has none. */
    private String nameBeforeConfiguration(ToolMethodSpec spec) {
        return spec.getName().isBlank() ? spec.getMethod().getName() : spec.getName();
    }

    private void apply(ToolMethodProperties configured, ToolMethodSpec spec) {
        if (configured.getName() != null) {
            spec.setName(configured.getName());
        }
        if (configured.getDescription() != null) {
            spec.setDescription(configured.getDescription());
        }
        if (configured.getType() != null) {
            spec.setType(configured.getType());
        }
        if (configured.getSchema() != null) {
            spec.setSchema(configured.getSchema());
        }
        if (configured.getStrict() != null) {
            spec.setStrict(configured.getStrict());
        }
        if (!configured.getExtras().isEmpty()) {
            ProviderExtras extras = spec.getExtras() == null ? new ProviderExtras() : spec.getExtras();
            configured.getExtras().forEach(extras::putRaw);
            spec.setExtras(extras);
        }
        for (ToolParameterSpec entry : spec.getParameters()) {
            ToolParameterProperties parameter = configured.getParameters().get(nameOf(entry));
            if (parameter != null) {
                apply(parameter, entry);
            }
        }
    }

    private static void apply(ToolParameterProperties configured, ToolParameterSpec entry) {
        if (configured.getName() != null) {
            entry.setName(configured.getName());
        }
        if (configured.getDescription() != null) {
            entry.setDescription(configured.getDescription());
        }
        if (configured.getRequired() != null) {
            entry.setRequired(configured.getRequired());
        }
        if (configured.getFromModel() != null) {
            entry.setFromModel(configured.getFromModel());
        }
        if (configured.getSchema() != null) {
            entry.setSchema(configured.getSchema());
        }
    }

    /** The name a parameter is found under: what its annotation named it, or the Java parameter's name. */
    private static String nameOf(ToolParameterSpec entry) {
        return entry.getName().isBlank() ? entry.getParameter().getName() : entry.getName();
    }

}
