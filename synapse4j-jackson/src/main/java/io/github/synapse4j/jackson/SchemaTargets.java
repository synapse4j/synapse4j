package io.github.synapse4j.jackson;

import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

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

    /**
     * The given annotation as Jackson resolves it on a class: the type's own declaration when it carries
     * one, otherwise the first type up its hierarchy that does. {@code Class.getAnnotation} reads only
     * the type's own declaration, which is why the chain is walked here — an annotation such as
     * {@code @JsonInclude} that the language does not inherit is still a class-level answer Jackson
     * takes from a parent.
     *
     * @param <A>            the annotation type
     * @param type           the type to read the annotation on; never {@code null}
     * @param annotationType the annotation to read; never {@code null}
     * @return the annotation, or {@code null} when no type up the chain carries it
     */
    static <A extends Annotation> @Nullable A classAnnotation(Class<?> type, Class<A> annotationType) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            A annotation = current.getAnnotation(annotationType);
            if (annotation != null) {
                return annotation;
            }
            Class<?> superclass = current.getSuperclass();
            if (superclass != null) {
                pending.addLast(superclass);
            }
            Collections.addAll(pending, current.getInterfaces());
        }
        return null;
    }

}
