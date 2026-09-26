package io.github.synapse4j.exception;

import org.jspecify.annotations.Nullable;

/**
 * A call the provider answered with a non-2xx status.
 *
 * <p>
 * The status is carried as a value rather than only spelled into the message because it is the one
 * part of a failure a caller can act on without knowing the provider's error vocabulary: whether to
 * wait, re-ask, fix credentials or give up branches on the status first. The message keeps whatever
 * detail the provider's body carried, as it always has.
 *
 * <p>
 * One class holds every status rather than a subclass per code: the set is large, closed by the HTTP
 * specification, and read as an integer far more often than it is caught as a type.
 */
public class SynapseHttpException extends SynapseException {

    private final int statusCode;

    /**
     * @param message    what the failure says, detail included
     * @param statusCode the HTTP status the answer carried
     */
    public SynapseHttpException(@Nullable String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * @return the HTTP status the answer carried
     */
    public int getStatusCode() {
        return statusCode;
    }

}
