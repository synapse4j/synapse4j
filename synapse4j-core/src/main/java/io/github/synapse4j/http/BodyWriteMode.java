package io.github.synapse4j.http;

import org.jspecify.annotations.Nullable;

/**
 * A {@link HttpOptions#getBodyWriteMode() body-write mode} as a value an implementation can reason
 * about, rather than the string the option carries.
 *
 * <p>
 * {@link #from(String)} is where the text becomes one of these: it answers the mode the text names or
 * refuses a value this library does not define, so the comparison is written once rather than spelled
 * out in every transport, and a spelling that differs only in case names the same mode.
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
