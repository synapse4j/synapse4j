package io.github.synapse4j.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides which properties the schema of the JSON this codec writes lists as required: those writing
 * always produces.
 *
 * <p>
 * The answer reads the mapper's default inclusion, which {@link #isAlwaysWritten} holds. A setting that
 * omits values leaves a primitive alone, which can be neither null nor empty, while a setting that omits
 * defaults takes a primitive with it. That is coarser than it could be — each property's own annotation
 * is not consulted — and safe in the direction that matters, because a schema that demands less than
 * writing produces is still one that writing satisfies. A member an annotation demands is demanded all
 * the same, by the Jackson module rather than here.
 */
@RequiredArgsConstructor
public class EncodeRequiredPropertiesModule implements Module {

    /** The mapper whose default inclusion answers what writing leaves out. */
    private final @NonNull JsonMapper jsonMapper;

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder configBuilder) {
        // A field and the getter over it are one property, and victools asks both; the same answer has
        // to be given for either, so both are registered.
        configBuilder.forFields().withRequiredCheck(this::isAlwaysWritten);
        configBuilder.forMethods().withRequiredCheck(this::isAlwaysWritten);
    }

    /**
     * Whether this application's writing always writes the given property out.
     *
     * <p>
     * The default reads the mapper's default inclusion. A setting that omits values leaves a primitive
     * alone, which can be neither null nor empty, and a setting that omits defaults takes a primitive
     * with it. Override it when the writing is not what that setting says — a custom serializer, or a
     * property whose own annotation decides.
     *
     * @param member the property being described; must not be {@code null}
     * @return whether the property is always written out
     */
    protected boolean isAlwaysWritten(MemberScope<?, ?> member) {
        JsonInclude.Include inclusion = jsonMapper.serializationConfig().getDefaultPropertyInclusion()
                .getValueInclusion();
        return switch (inclusion) {
            case NON_DEFAULT -> false;
            case NON_NULL, NON_ABSENT, NON_EMPTY -> member.getDeclaredType().getErasedType().isPrimitive();
            default -> true;
        };
    }

}
