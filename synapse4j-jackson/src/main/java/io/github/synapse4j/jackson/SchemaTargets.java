package io.github.synapse4j.jackson;

import com.github.victools.jsonschema.generator.MemberScope;

/**
 * The type a victools member is being described for, which is what Jackson consults when it moves that
 * member.
 *
 * <p>
 * It is not always the member's declaring type: an inherited member is written by the subclass, as the
 * subclass's own introspection says — its class-level inclusion is the subclass's, its place in the
 * order is the subclass's — so asking the declaring type describes the subclass the way its parent is
 * written. Every module that reads a class-level answer has to ask this same question.
 */
final class SchemaTargets {

    private SchemaTargets() {
    }

    /**
     * The type the schema is being generated for.
     *
     * @param member the member being described; never {@code null}
     * @return the schema target type, erased; never {@code null}
     */
    static Class<?> of(MemberScope<?, ?> member) {
        return member.getDeclarationDetails().getSchemaTargetType().getErasedType();
    }

}
