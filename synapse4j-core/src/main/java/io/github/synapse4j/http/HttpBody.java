package io.github.synapse4j.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The bytes of one request body: written into the sink an {@link HttpClient} implementation hands
 * over, or handed over as a buffer when they are already in hand.
 *
 * <p>
 * The body is pushed, not pulled: writing is what produces it, whether that is a serializer emitting
 * into a stream or a caller handing over bytes it already has. An implementation whose HTTP library
 * pulls instead adapts on its own side, and nothing here promises how the bytes travel.
 *
 * <p>
 * {@link #writeTo(OutputStream)} may be called more than once, and every call has to produce the same
 * bytes: a request is sent again on a retry, on a redirect, and after an authentication challenge. A
 * source that can only be read once — a socket, a one-shot pipe — is therefore something the caller
 * buffers before handing it in. Calls are sequential: one call at a time, never two at once.
 *
 * <p>
 * {@link #buffer()} is the same content where producing it would be work already done. An
 * implementation may take it instead of asking for a write, which is what keeps bytes a caller already
 * holds from being written out and read back — and it is where a length comes from when there is one:
 * the buffer knows how much it holds, while a body that can only be written is a body whose length is
 * not known until it has been.
 *
 * <p>
 * The sink belongs to the implementation, so {@link #writeTo(OutputStream)} never closes it.
 */
@FunctionalInterface
public interface HttpBody {

    /**
     * Writes this body's bytes to the given sink.
     *
     * @param out where the bytes go; never closed by this method
     * @throws IOException if the body cannot be produced, or the sink fails
     */
    void writeTo(OutputStream out) throws IOException;

    /**
     * The body's bytes, when they are already in hand; {@code null} when writing is the only way to
     * produce them.
     *
     * <p>
     * Every call answers with a fresh buffer — for a body that holds one, a duplicate of it — reading
     * from its first byte to its last, so a caller may consume the buffer it was given without the body
     * noticing and without a second attempt seeing a moved position. What the buffer wraps is not to be
     * modified while a request carrying it is in flight.
     *
     * <p>
     * Answering {@code null} is the default and is always correct: a body answers here only because it
     * can save the work of writing itself out.
     *
     * @return the content, or {@code null} when this body has to be written to be read
     */
    default ByteBuffer buffer() {
        return null;
    }

    /**
     * A body of bytes already in hand. The array is taken as it is, not copied, so a caller keeps its
     * hands off it while the request is in flight.
     *
     * @param bytes the body; never {@code null}
     */
    static HttpBody of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        return new HttpBody() {

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(bytes);
            }

            @Override
            public ByteBuffer buffer() {
                return ByteBuffer.wrap(bytes);
            }
        };
    }

    /**
     * A body of text, in UTF-8 — the encoding every JSON body here is written in.
     *
     * @param text the body; never {@code null}
     */
    static HttpBody of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /**
     * A body of text in the given charset.
     *
     * @param text    the body; never {@code null}
     * @param charset the charset the text is encoded in; never {@code null}
     */
    static HttpBody of(String text, Charset charset) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        return of(text.getBytes(charset));
    }

}
