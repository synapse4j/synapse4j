package io.github.synapse4j.jackson;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;

import io.github.synapse4j.json.JsonSchemaKeywords;
import tools.jackson.databind.node.ObjectNode;

/**
 * Describes a {@code byte[]} as a base64 string, which is what the mapper writes and reads by default.
 *
 * <p>
 * Victools describes a {@code byte[]} as an array of strings, which no mapper produces: Jackson encodes
 * it as base64 text, in both directions, unless a serializer of the application's own says otherwise. A
 * schema that says array where the binder writes a string is one the contract forbids — the codec would
 * not accept the document the schema allowed — so this rule corrects it to the string it is.
 *
 * <p>
 * The encoding is named as well, {@code contentEncoding: base64}. It is an annotation rather than an
 * assertion — a validator is not asked to act on it, and no provider this library talks to reads it —
 * but it costs a keyword and documents the string for whoever does look. A mapper that writes a
 * {@code byte[]} some other way is not described here: turning this rule off and supplying a module of
 * one's own is how that is replaced.
 */
public class Base64BytesModule implements Module {

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder configBuilder) {
        configBuilder.forTypesInGeneral().withCustomDefinitionProvider((javaType, context) -> {
            if (javaType.getErasedType() != byte[].class) {
                return null;
            }
            ObjectNode definition = context.getGeneratorConfig().createObjectNode();
            definition.put(context.getKeyword(SchemaKeyword.TAG_TYPE),
                    context.getKeyword(SchemaKeyword.TAG_TYPE_STRING));
            // An annotation, not an assertion: a validator need not act on it, but it documents the string.
            definition.put(JsonSchemaKeywords.CONTENT_ENCODING, "base64");
            return new CustomDefinition(definition, CustomDefinition.DefinitionType.INLINE,
                    CustomDefinition.AttributeInclusion.NO);
        });
    }

}
