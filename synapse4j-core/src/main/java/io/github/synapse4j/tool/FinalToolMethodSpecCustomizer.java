package io.github.synapse4j.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.synapse4j.data.ChatContext;
import io.github.synapse4j.json.JsonCodec;
import io.github.synapse4j.json.JsonSchema;
import io.github.synapse4j.json.JsonSchemaBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The step that turns what the annotations and the customizers wrote into the answer: every string
 * still blank becomes what the method itself says it should be.
 *
 * <p>
 * It runs last, after every other {@link ToolMethodSpecCustomizer}, and that is what makes it the
 * fallback rather than another voice: whatever a customizer wrote is left alone, and only what nobody
 * wrote is settled here. Which means a customizer that wants a different answer does not have to
 * replace this step — it writes the value it wants, and this one finds nothing left to do.
 *
 * <p>
 * Per parameter it settles, in this order: the name it is declared under, whether the model produces
 * the argument at all, the schema of its property when it does, and whether the model has to produce
 * it. For the tool as a whole it settles the name and the input schema the model is shown, the latter
 * assembled out of what each parameter settled on.
 *
 * <p>
 * A schema is settled once: what a customizer wrote — as text in {@code schema}, or as a
 * {@link JsonSchema} already resolved — is what the declaration carries, and only a parameter nothing
 * was written for has one derived from its type.
 */
@RequiredArgsConstructor
public class FinalToolMethodSpecCustomizer implements ToolMethodSpecCustomizer {

    /** The codec the schemas are derived with, and a caller's own is bound with; never {@code null}. */
    @NonNull
    private final JsonCodec codec;

    @Override
    public void customize(@NonNull ToolMethodSpec spec) {
        if (spec.getName().isBlank()) {
            spec.setName(spec.getMethod().getName());
        }
        for (ToolParameterSpec entry : spec.getParameters()) {
            settle(entry);
        }
        if (spec.getResolvedSchema() == null) {
            spec.setResolvedSchema(inputSchemaOf(spec));
        }
    }

    /**
     * Settles one parameter's answers, writing each into the entry it belongs to: the name after the
     * Java parameter, whether the model produces it, the schema of its property, and whether the model
     * has to produce it — the last read off the schema just settled, so an argument's own schema is
     * derived once.
     *
     * <p>
     * A parameter the model does not produce settles only the first two: it has no property in the
     * input schema, so neither a schema nor a required word of its own means anything.
     *
     * @param entry the parameter to settle; never {@code null}
     */
    private void settle(ToolParameterSpec entry) {
        if (entry.getName().isBlank()) {
            entry.setName(entry.getParameter().getName());
        }
        if (entry.getFromModel().isBlank()) {
            entry.setFromModel(String.valueOf(comesFromTheModel(entry)));
        }
        if (!entry.fromModel()) {
            if (entry.getValueProvider() == null
                    && ChatContext.class.isAssignableFrom(entry.getParameter().getType())) {
                entry.setValueProvider(conversation());
            }
            return;
        }
        if (entry.getResolvedSchema() == null) {
            entry.setResolvedSchema(schemaOf(entry));
        }
        if (entry.getRequired().isBlank()) {
            entry.setRequired(String.valueOf(requiredByTheCodec(entry)));
        }
    }

    /**
     * Whether the model produces this argument, judged from the parameter itself when nobody said: a
     * {@link ChatContext} parameter is filled from the conversation rather than by the model, and
     * anything else is the model's to produce.
     */
    private static boolean comesFromTheModel(ToolParameterSpec entry) {
        return !ChatContext.class.isAssignableFrom(entry.getParameter().getType());
    }

    /**
     * The value a {@link ChatContext} parameter takes: the conversation this call belongs to,
     * {@code null} included. Settled here rather than known to {@link MethodTool}, so that the one
     * place a tool's arguments come from is this step.
     */
    private static ToolParameterValueProvider conversation() {
        return (tool, parameter, context) -> context;
    }

    /**
     * The schema of this parameter's property: the one a customizer resolved, or the document it wrote,
     * read; and where nobody wrote either, the one the codec derives from the declared type.
     */
    private JsonSchema schemaOf(ToolParameterSpec entry) {
        return entry.getSchema().isBlank() ? codec.generateDecodeSchema(entry.getParameter().getParameterizedType())
                : schemaFrom(entry.getSchema());
    }

    /**
     * Whether the model has to produce this argument, judged by the codec through a
     * {@link RequiredProbe}: the value is placed in a property's position, so a type the codec makes
     * optional (an {@link java.util.Optional}, say) is not required, and anything else is.
     */
    private boolean requiredByTheCodec(ToolParameterSpec entry) {
        return RequiredProbe.isRequired(
                codec.generateDecodeSchema(RequiredProbe.wrapping(entry.getParameter().getParameterizedType())));
    }

    /** The input schema the model is shown: the document a customizer wrote, or the assembled envelope. */
    private JsonSchema inputSchemaOf(ToolMethodSpec spec) {
        if (!spec.getSchema().isBlank()) {
            return schemaFrom(spec.getSchema());
        }
        JsonSchemaBuilder envelope = new JsonSchemaBuilder().setType("object");
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameterSpec entry : spec.getParameters()) {
            if (!entry.fromModel()) {
                // An argument the model does not produce is no property of the schema the model is
                // shown, whatever schema a customizer resolved for it.
                continue;
            }
            JsonSchema property = entry.getResolvedSchema();
            if (property == null) {
                continue;
            }
            if (property.asBoolean() == null && !entry.getDescription().isBlank()) {
                property = JsonSchemaBuilder.from(property).setDescription(entry.getDescription()).build();
            }
            properties.put(entry.getName(), property);
            if (entry.isRequired()) {
                required.add(entry.getName());
            }
        }
        if (!properties.isEmpty()) {
            envelope.setProperties(properties);
        }
        if (!required.isEmpty()) {
            envelope.setRequired(required);
        }
        return envelope.build();
    }

    /**
     * A schema a caller wrote as a JSON document, read back with the codec — the one codec that knows
     * this library's own schema type.
     *
     * @param document the schema as JSON text; never {@code null}, and never blank
     * @return the schema it spells; never {@code null}
     */
    private JsonSchema schemaFrom(String document) {
        JsonSchema schema = codec.decode(document, JsonSchema.class);
        if (schema == null) {
            throw new IllegalArgumentException("the schema written for this parameter is empty: " + document);
        }
        return schema;
    }
}