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
 * The answer reads a property's own inclusion, which {@link #isAlwaysWritten} holds, and falls back to
 * what the class it sits in asks for and then to the mapper's default when the property carries none. A
 * setting that omits values leaves a primitive alone, which can be neither null nor empty, while a
 * setting that omits defaults takes a primitive with it. A member an annotation demands is demanded all
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
     * The answer reads the property's own inclusion, falling back to the mapper's default when the
     * property carries none. A setting that omits values leaves a primitive alone, which can be neither
     * null nor empty, and a setting that omits defaults takes a primitive with it. Override it when the
     * writing is not what that setting says — a custom serializer, for instance.
     *
     * @param member the property being described; must not be {@code null}
     * @return whether the property is always written out
     */
    protected boolean isAlwaysWritten(MemberScope<?, ?> member) {
        return switch (inclusionOf(member)) {
            case NON_DEFAULT -> false;
            case NON_NULL, NON_ABSENT, NON_EMPTY -> member.getDeclaredType().getErasedType().isPrimitive();
            default -> true;
        };
    }

    /**
     * The inclusion this property is written by: what it says itself, then what the class it sits in
     * says, then the mapper's default — the order Jackson resolves them in, where {@code USE_DEFAULTS}
     * asks for the next one down rather than naming an inclusion of its own.
     *
     * @param member the property being described; must not be {@code null}
     * @return the inclusion writing applies to it; never {@code null}
     */
    private JsonInclude.Include inclusionOf(MemberScope<?, ?> member) {
        JsonInclude own = member.getAnnotationConsideringFieldAndGetterIfSupported(JsonInclude.class);
        if (own != null && own.value() != JsonInclude.Include.USE_DEFAULTS) {
            return own.value();
        }
        JsonInclude onType = member.getDeclaringType().getErasedType().getAnnotation(JsonInclude.class);
        if (onType != null && onType.value() != JsonInclude.Include.USE_DEFAULTS) {
            return onType.value();
        }
        return jsonMapper.serializationConfig().getDefaultPropertyInclusion().getValueInclusion();
    }

}
