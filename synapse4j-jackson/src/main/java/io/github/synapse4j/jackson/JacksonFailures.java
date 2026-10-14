package io.github.synapse4j.jackson;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;

/**
 * The translation every Jackson call this module makes goes through: Jackson's own failures become
 * this library's — an I/O failure a {@link SynapseIOException}, anything else a
 * {@link SynapseException} — each under the message the caller wrote for the call it was making.
 *
 * <p>
 * A failure this library raised itself while the call ran is not Jackson's to rename: Jackson wraps
 * whatever escapes a serializer, and that wrapper would stand in for the reason. Such a failure is
 * handed back as itself.
 *
 * <p>
 * One home because every such call meets the same two Jackson failures, and a caller is promised
 * this library's exceptions rather than Jackson's. A holder of one helper rather than a data class:
 * it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class JacksonFailures {

    /**
     * Runs one call, translating Jackson's failures into this library's. The answer's nullness is
     * the supplier's, so a call whose supplier always answers is not checked again for a null it
     * cannot produce.
     *
     * @param message the message the failure is reported under
     * @param call    the call to run; never {@code null}
     * @return what the call answered
     */
    static <T> T translate(String message, Supplier<T> call) {
        try {
            return call.get();
        } catch (JacksonIOException failure) {
            throw new SynapseIOException(message, failure.getCause());
        } catch (JacksonException failure) {
            SynapseException own = ownFailureIn(failure);
            if (own != null) {
                throw own;
            }
            throw new SynapseException(message, failure);
        }
    }

    /**
     * The library's own failure inside the given one, where Jackson wrapped it on the way out. The
     * library's own type is the one thing recognizable here without a guess about how Jackson wraps,
     * and a failure the library raised is not Jackson's to rename.
     *
     * @param failure what Jackson threw; never {@code null}
     * @return the library's own failure, or {@code null} when the failure is Jackson's own
     */
    private static @Nullable SynapseException ownFailureIn(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SynapseException own) {
                return own;
            }
        }
        return null;
    }

}
