package io.github.synapse4j.jackson;

import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.TypeScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Closes an object against properties its schema does not list.
 *
 * <p>
 * A decode schema is a contract for whoever produces the JSON, so it should say what the binder does:
 * where the reading refuses a property the JSON leaves undeclared, an object that lists properties is
 * described as closed — {@code additionalProperties: false}. Whether the reading refuses one is a mapper
 * setting, so a caller registers this module only where it holds; {@link JacksonSchemaConfigBuilders}
 * does so, and the check is made once when the generator is built rather than per node.
 *
 * <p>
 * Only an object that lists properties is closed. A node carrying none is left as it is, because a type
 * whose properties the generator could not enumerate — an interface, a type a serializer of the
 * application's own settles — would otherwise be described as admitting nothing at all, and a producer
 * told that can only answer the one document the binder then refuses. A node that already states
 * {@code additionalProperties} — a map's value schema — keeps it.
 */
public class DisfavoredAdditionalPropertiesModule implements Module {

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder configBuilder) {
        configBuilder.forTypesInGeneral().withTypeAttributeOverride(this::disfavor);
    }

    /** The last word on a type's node: close it against undeclared properties, or leave it as it is. */
    private void disfavor(ObjectNode node, TypeScope scope, SchemaGenerationContext context) {
        if (!isObject(node, context)
                || node.has(context.getKeyword(SchemaKeyword.TAG_ADDITIONAL_PROPERTIES))
                || !listsProperties(node, context)) {
            return;
        }
        node.put(context.getKeyword(SchemaKeyword.TAG_ADDITIONAL_PROPERTIES), false);
    }

    /** Whether the node describes an object, its {@code type} written as one value or as several. */
    private static boolean isObject(ObjectNode node, SchemaGenerationContext context) {
        JsonNode type = node.get(context.getKeyword(SchemaKeyword.TAG_TYPE));
        if (type == null) {
            return false;
        }
        String object = context.getKeyword(SchemaKeyword.TAG_TYPE_OBJECT);
        if (type.isString()) {
            return object.equals(type.asString());
        }
        if (type.isArray()) {
            for (JsonNode alternative : type) {
                if (object.equals(alternative.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the node lists at least one property, which is what makes closing it meaningful. */
    private static boolean listsProperties(ObjectNode node, SchemaGenerationContext context) {
        JsonNode properties = node.get(context.getKeyword(SchemaKeyword.TAG_PROPERTIES));
        return properties != null && properties.isObject() && !properties.isEmpty();
    }

}
