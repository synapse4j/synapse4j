package io.github.synapse4j.http;

import org.jspecify.annotations.Nullable;

/**
 * The body-write modes this library itself supports, as values an implementation can reason about
 * rather than the strings {@link HttpOptions#getBodyWriteMode()} carries.
 *
 * <p>
 * The set is closed: it holds the modes this library defines, and nothing else. A transport that
 * supports a mode of its own carries that mode's text in {@link HttpOptions#getBodyWriteMode()} like
 * any other and recognizes the text itself, before asking {@link #from(String)} — which answers for
 * this set alone and refuses the rest. Writing the comparison here is what keeps it in one place
 * instead of spelling it out in every transport, and what makes a spelling that differs only in case
 * name the same mode.
 */
public enum BodyWriteMode {

    /** The implementation picks the mode it does best; the mode {@link HttpOptions#defaults()} carries. */
    AUTO("auto"),

    /**
     * The body is written as it comes, keeping it out of memory. An implementation that cannot take a
     * written body on the caller's thread converts it on a thread of its own; one that can do neither
     * refuses the mode rather than gathering a body the caller asked not to gather.
     */
    STREAMED("streamed"),

    /** The body is gathered into memory before it is sent. */
    BUFFERED("buffered");

    private final String value;

    BodyWriteMode(String value) {
        this.value = value;
    }

    /**
     * The string this mode is carried as in {@link HttpOptions#getBodyWriteMode()}.
     *
     * @return the mode's string form
     */
    public String value() {
        return value;
    }

    /**
     * The mode the given text names, refusing one this library does not define rather than taking it
     * for the default: a caller who asked for one thing must not silently get another. The comparison
     * ignores case.
     *
     * @param value the mode as {@link HttpOptions#getBodyWriteMode()} carries it
     * @return the mode the text names
     * @throws IllegalArgumentException if the text names no mode this library defines
     */
    public static BodyWriteMode from(@Nullable String value) {
        for (BodyWriteMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported bodyWriteMode: " + value);
    }
}
