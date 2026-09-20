package io.github.synapse4j.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The bytes of one request body, written into the sink an {@link HttpClient} implementation hands
 * over.
 *
 * <p>
 * The body is pushed, not pulled: writing is what produces it, whether that is a serializer
 * emitting into a stream or a caller handing over bytes it already has. An implementation whose HTTP
 * library pulls instead adapts on its own side, and nothing here promises how the bytes travel.
 *
 * <p>
 * {@link #writeTo(OutputStream)} may be called more than once, and every call has to produce the same
 * bytes: a request is sent again on a retry, on a redirect, and after an authentication challenge. A
 * source that can only be read once — a socket, a one-shot pipe — is therefore something the caller
 * buffers before handing it in. Calls are sequential: one call at a time, never two at once.
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
     * The number of bytes, or {@code -1} when that is not known until they are written.
     *
     * <p>
     * Unknown is the honest answer and the usual one here: a request body is normally a serialization
     * that is still being produced, so its length is not there to be asked for. An implementation
     * takes the answer as a hint — it decides whether the request goes out with a
     * {@code Content-Length} or is framed some other way — and must not require it to be known. The
     * factories below answer with their length because they hold the bytes already, which is what
     * lets those go out without being gathered first.
     *
     * @return the length in bytes, or {@code -1} if it is not known
     */
    default long length() {
        return -1;
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
            public long length() {
                return bytes.length;
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

    /**
     * A body of a file's content, so that a large one need never be held in memory.
     *
     * <p>
     * The length is the file's, read once when this body is created — the only moment there is to read
     * it, since an implementation decides how to frame a request before a byte of it is written. A file
     * that changes size in between would therefore make the request disagree with itself, so a write
     * that does not deliver exactly that many bytes fails instead of passing for a complete body. A
     * size that cannot be read at all is answered as unknown, and the file failing for real surfaces
     * when it is written — where a failing read belongs.
     *
     * <p>
     * The file is expected to hold still while a request carrying it is in flight: a change that keeps
     * the size is not something a count can catch, and a body answering with other bytes on a second
     * attempt breaks the rule this interface is built on.
     *
     * @param file the file to send; never {@code null}
     */
    static HttpBody ofFile(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        long size = sizeOrUnknown(file);
        return new HttpBody() {

            @Override
            public void writeTo(OutputStream out) throws IOException {
                long written = Files.copy(file, out);
                if (size >= 0 && written != size) {
                    throw new IOException("the file changed while it was being sent: " + file + " was "
                            + size + " bytes, " + written + " bytes were written");
                }
            }

            @Override
            public long length() {
                return size;
            }
        };
    }

    private static long sizeOrUnknown(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unknown) {
            return -1;
        }
    }

}
