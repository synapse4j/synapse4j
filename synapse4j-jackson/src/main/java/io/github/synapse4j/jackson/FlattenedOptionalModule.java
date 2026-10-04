package io.github.synapse4j.jackson;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.fasterxml.classmate.ResolvedType;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;

import tools.jackson.databind.node.ObjectNode;

/**
 * Flattens an {@code Optional} to its value's schema, made nullable — in every position.
 *
 * <p>
 * Victools' own {@link Option#FLATTENED_OPTIONALS} is turned off, because it works on a member's
 * declared type: {@code FlattenedWrapperModule} installs a target type override on fields and methods
 * only, which leaves the same type described as a bare object in the root position and as its value
 * type without the null inside a list or a map. Here one rule answers every position, so a member needs
 * no nullable check of its own and the answer cannot depend on where the type was written.
 *
 * <p>
 * A wrapper this class does not know — an application's own {@code Maybe<T>}, say — is added by
 * overriding {@link #isOptional} and {@link #valueTypeOf}: the two answers belong together, and together
 * they are what this rule describes a value by.
 *
 * <p>
 * A wrapper {@link #isOptional} does not accept is not flattened, and is described as the bare object
 * victools sees: with the default {@link Option#FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT} that is
 * {@code {type: object, additionalProperties: false}} — a schema admitting no property at all. An
 * application adding a {@code Maybe<T>} therefore gets that empty object until it overrides
 * {@link #isOptional} to accept the type; overriding {@link #valueTypeOf} alone changes nothing.
 */
public class FlattenedOptionalModule implements Module {

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder configBuilder) {
        configBuilder.without(Option.FLATTENED_OPTIONALS);
        configBuilder.forTypesInGeneral().withCustomDefinitionProvider((javaType, context) -> {
            if (!isOptional(javaType)) {
                return null;
            }
            return new CustomDefinition(nullableValue(valueTypeOf(javaType, context), context),
                    CustomDefinition.DefinitionType.INLINE, CustomDefinition.AttributeInclusion.NO);
        });
    }

    /**
     * Whether a type says its value may be absent.
     *
     * <p>
     * The default answers for an {@code Optional} and its primitive kin. Override to add a wrapper of
     * your own, and override {@link #valueTypeOf} for the value it carries — the two answers belong
     * together.
     *
     * @param type the type to check; must not be {@code null}
     * @return whether the type is one whose value may be absent
     */
    protected boolean isOptional(ResolvedType type) {
        Class<?> erased = type.getErasedType();
        return erased == Optional.class || erased == OptionalInt.class || erased == OptionalLong.class
                || erased == OptionalDouble.class;
    }

    /**
     * The type such a wrapper carries: for an {@code Optional} its type argument, and for the three
     * primitives that carry their value without one, the boxed type. Override alongside
     * {@link #isOptional}.
     *
     * @param optional the wrapper type, which {@link #isOptional} has accepted; must not be
     *                     {@code null}
     * @param context  the generation in progress, for resolving a type of your own; must not be
     *                     {@code null}
     * @return the type the wrapper carries; never {@code null}
     */
    protected ResolvedType valueTypeOf(ResolvedType optional, SchemaGenerationContext context) {
        Class<?> erased = optional.getErasedType();
        if (erased == OptionalInt.class) {
            return context.getTypeContext().resolve(Integer.class);
        }
        if (erased == OptionalLong.class) {
            return context.getTypeContext().resolve(Long.class);
        }
        if (erased == OptionalDouble.class) {
            return context.getTypeContext().resolve(Double.class);
        }
        return optional.getTypeParameters().get(0);
    }

    /**
     * The value's schema beside a null one, as an {@code anyOf}.
     *
     * <p>
     * The arrangement is what the custom-definition machinery leaves open. It copies the returned node
     * into the definition it keeps for the encountered type, so a reference created by
     * {@code createDefinitionReference} has to sit <em>inside</em> that node to stay linked — the
     * documentation's own example nests one as a property value for the same reason. And a reference
     * cannot simply gain {@code "null"} in its type, which is why the null alternative is a branch
     * rather than another type: {@code makeNullable} decides between the two by looking at the node it
     * is given, and a reference is still an empty placeholder when the provider returns.
     *
     * <p>
     * Referencing rather than inlining the value's schema is what keeps a recursive type finite:
     * {@code Node} holding an {@code Optional<Node>} comes out as {@code anyOf [{$ref: #}, {type: null}]}
     * instead of a schema that contains itself. Inlining it instead — {@code createDefinition} or
     * {@code createStandardDefinition} — describes the same type correctly but recurses without end on
     * exactly that shape.
     *
     * <p>
     * The definition itself is declared {@link CustomDefinition.DefinitionType#INLINE}: it describes a
     * type in place rather than being swept into {@code $defs} and pointed at by a {@code $ref} wherever
     * the same optional value is written twice. What stays referenced is the value's own schema, which
     * is the one a cycle needs, and a genuine one keeps its {@code $ref} either way.
     */
    private static ObjectNode nullableValue(ResolvedType valueType, SchemaGenerationContext context) {
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        definition.putArray(context.getKeyword(SchemaKeyword.TAG_ANYOF))
                .add(context.createDefinitionReference(valueType))
                .addObject()
                .put(context.getKeyword(SchemaKeyword.TAG_TYPE), context.getKeyword(SchemaKeyword.TAG_TYPE_NULL));
        return definition;
    }

}
