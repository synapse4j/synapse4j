package io.github.synapse4j.jackson;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.fasterxml.classmate.ResolvedType;

/**
 * The wrappers whose value may be absent, as both of this module's rules read them: the one that
 * describes an optional's schema and the one that decides whether a property is required. They answer
 * the same question about the same types, so the answer is written once — a wrapper outside this set
 * is one the application answers for itself, through the hook it overrides.
 */
final class OptionalTypes {

    private OptionalTypes() {
    }

    /** Whether the type is one of the wrappers this module answers for by default. */
    static boolean isOptional(ResolvedType type) {
        Class<?> erased = type.getErasedType();
        return erased == Optional.class || erased == OptionalInt.class || erased == OptionalLong.class
                || erased == OptionalDouble.class;
    }
}
