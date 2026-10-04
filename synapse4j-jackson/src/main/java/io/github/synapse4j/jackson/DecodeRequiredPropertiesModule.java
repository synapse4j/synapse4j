package io.github.synapse4j.jackson;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.fasterxml.classmate.ResolvedType;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides which properties the schema of the JSON this codec reads lists as required.
 *
 * <p>
 * That is what the type asks for: a value {@link #isOptionalType} calls optional may be absent, and
 * anything else may not. It is deliberately stricter than the binder, which reads an absent reference as
 * null without complaining — the schema is the contract a producer is held to, not a list of everything
 * a lenient binder tolerates. A member annotated to be required is demanded by the Jackson module rather
 * than here, which the defaults enable: an annotation can add to what the type asks for and never take
 * away from it, since the required decision is one union of every check and none of them can veto
 * another.
 *
 * <p>
 * A decode schema demands every property, and a mapper set to refuse a creator property the JSON leaves
 * out is the one setting consulted beyond the type: it widens the demand to the optional properties too,
 * which stay nullable in their place. That setting is what keeps the demand no stricter than binding for
 * a property that arrives on a creator — the binder already refuses the document, so the schema asks for
 * nothing the reading would not. For a property that does not arrive on a creator, a setter-only bean's
 * say, the binder refuses nothing, and the demand is the schema's deliberate strictness over a reading
 * that would accept the absence.
 *
 * <p>
 * What this class cannot know is the wrappers an application has of its own: {@link #isOptionalType}
 * holds that answer, the JDK's optionals by default, and a subclass extends it or hands the question to
 * whatever else answers it — {@link FlattenedOptionalModule#isOptional} being one such answer, where the
 * description of an optional value is the thing to keep in step with.
 */
@RequiredArgsConstructor
public class DecodeRequiredPropertiesModule implements Module {

    /** The mapper whose setting answers whether a creator property that is left out is refused. */
    private final @NonNull JsonMapper jsonMapper;

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder configBuilder) {
        // A field and the getter over it are one property, and victools asks both; the same answer has
        // to be given for either, so both are registered.
        configBuilder.forFields().withRequiredCheck(this::isRequired);
        configBuilder.forMethods().withRequiredCheck(this::isRequired);
    }

    /**
     * Whether this rule demands the given property.
     *
     * <p>
     * Override to extend the rule rather than to replace it — take {@code super} for the answer below,
     * and add what the application demands on top of it.
     *
     * @param member the property being described; must not be {@code null}
     * @return whether the schema is to list the property as required
     */
    protected boolean isRequired(MemberScope<?, ?> member) {
        // The declared type, not the effective one: another module may have overridden what the member
        // is described as — victools' own Optional handling does exactly that — and what a type makes
        // optional is a question about the declaration, which no override changes.
        return refusesMissingCreators() || !isOptionalType(member.getDeclaredType());
    }

    /**
     * Whether a type says its value may be absent.
     *
     * <p>
     * The default answers for an {@code Optional} and its primitive kin. Override it to add a wrapper of
     * your own, or to hand the question to whatever else answers it — an
     * {@link FlattenedOptionalModule#isOptional} being one such answer, where the description of an
     * optional value is the thing to keep in step with.
     *
     * @param type the type to check; must not be {@code null}
     * @return whether the type is one whose value may be absent
     */
    protected boolean isOptionalType(ResolvedType type) {
        Class<?> erased = type.getErasedType();
        return erased == Optional.class || erased == OptionalInt.class || erased == OptionalLong.class
                || erased == OptionalDouble.class;
    }

    /**
     * Whether this application's reading refuses a property the JSON leaves out, when it arrives on a
     * creator. The default reads one mapper feature, the one that answers exactly that. Override it when
     * the reading refuses such a document for a reason of its own — a custom deserializer, say.
     *
     * @return whether a creator property that is left out is refused
     */
    protected boolean refusesMissingCreators() {
        return jsonMapper.deserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

}
