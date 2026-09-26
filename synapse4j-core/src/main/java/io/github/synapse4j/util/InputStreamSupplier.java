package io.github.synapse4j.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import lombok.NonNull;

/**
 * A source of bytes that can be opened more than once: every call answers with a stream over the same
 * content, which is what lets a request be sent again — on a retry, on a redirect, after an
 * authentication challenge — without the content having been held in memory to make that possible.
 *
 * <p>
 * The caller owns the stream it is given: it reads it, and it closes it. A source that cannot be
 * opened again — a socket, a stream that has already been handed out — is not one of these; where a
 * wrapper below accepts one, it says so.
 *
 * <p>
 * Named after {@link Supplier} on purpose: the shape is the same, and the one difference is the
 * failure this interface expects. Opening a source is I/O, and a failure to do it is an
 * {@link IOException} rather than something every call site has to wrap by hand.
 */
@FunctionalInterface
public interface InputStreamSupplier {

    /**
     * Opens a stream over the content.
     *
     * @return a stream for the caller to read and close; never {@code null}
     * @throws IOException if the content cannot be opened
     */
    InputStream get() throws IOException;

    /**
     * A source of bytes already in hand. The array is taken as it is, not copied, so a caller keeps its
     * hands off it while it may still be read.
     *
     * @param bytes the content; never {@code null}
     */
    static InputStreamSupplier of(@NonNull byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    /**
     * A source of text, in UTF-8.
     *
     * @param text the content; never {@code null}
     */
    static InputStreamSupplier of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /**
     * A source of text in the given charset.
     *
     * @param text    the content; never {@code null}
     * @param charset the charset the text is encoded in; never {@code null}
     */
    static InputStreamSupplier of(@NonNull String text, @NonNull Charset charset) {
        return of(text.getBytes(charset));
    }

    /**
     * A source of a file's content, opened when it is asked for rather than read when it is named: the
     * file is read as it is at that moment, and a file that is gone fails then.
     *
     * @param file the file; never {@code null}
     */
    static InputStreamSupplier of(@NonNull Path file) {
        return () -> Files.newInputStream(file);
    }

    /**
     * A source a caller already has, in the shape {@link Supplier} gives it. Nothing is thrown where
     * that shape cannot throw, so a failure to open arrives as whatever the supplier itself throws.
     *
     * @param supplier the supplier to adapt; never {@code null}
     */
    static InputStreamSupplier of(@NonNull Supplier<? extends InputStream> supplier) {
        return supplier::get;
    }

    /**
     * A source that can be opened once, for a stream that is already open: the first call answers with
     * that stream and every call after it fails. It is here so that a caller holding one stream can say
     * what it has, rather than writing the same thing by hand and leaving the limit unsaid.
     *
     * @param stream the stream; never {@code null}
     * @throws IllegalStateException from the second call on, since asking a single-use source for its
     *                                   content again is the caller's mistake rather than a failure of
     *                                   the content
     */
    static InputStreamSupplier once(@NonNull InputStream stream) {
        return new InputStreamSupplier() {

            private final AtomicBoolean opened = new AtomicBoolean();

            @Override
            public InputStream get() {
                if (!opened.compareAndSet(false, true)) {
                    throw new IllegalStateException("this source opens once, and it has already been opened");
                }
                return stream;
            }
        };
    }

}
