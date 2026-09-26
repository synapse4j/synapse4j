package io.github.synapse4j.exception;

import org.jspecify.annotations.Nullable;

/**
 * The base of everything this library throws.
 *
 * <p>
 * These exceptions are unchecked: a failed model call is not a condition the caller is expected to
 * recover from at the point of the call, and where it is handled — retried, worked around, surfaced —
 * is the caller's decision, often made far from the call itself.
 *
 * <p>
 * The base deliberately carries nothing but message and cause. Structured error details — HTTP
 * status, provider error payloads, retryability — belong on subclasses grown from real need, in the
 * layer that has the information, not on a base class designed ahead of any caller that would use
 * them.
 */
public class SynapseException extends RuntimeException {

    public SynapseException(@Nullable String message) {
        super(message);
    }

    public SynapseException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

}
