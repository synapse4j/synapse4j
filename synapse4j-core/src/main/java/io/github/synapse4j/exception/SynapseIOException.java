package io.github.synapse4j.exception;

import java.io.IOException;

/**
 * A failure of the stream underneath: the sink or source a writer or reader was given could not be
 * written to or read from.
 *
 * <p>
 * The original {@link IOException} is the cause, and {@link #getCause()} narrows to it, so a caller
 * that has to tell a broken transport from a document this library or its peer got wrong asks for
 * the type instead of inspecting causes. Everything else this library reports stays on
 * {@link SynapseException} itself.
 *
 * <p>
 * Subclassing the base rather than throwing {@link java.io.UncheckedIOException} keeps one family to
 * catch — a caller handling a failed call writes one handler — while the transport detail remains
 * available to whoever needs it. The choice is the one Jackson 3 made for the same two reasons.
 */
public class SynapseIOException extends SynapseException {

    /**
     * @param message what was being done when the stream failed
     * @param cause   the stream failure
     */
    public SynapseIOException(String message, IOException cause) {
        super(message, cause);
    }

    @Override
    public IOException getCause() {
        return (IOException) super.getCause();
    }

}
