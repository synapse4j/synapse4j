package io.github.synapse4j.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.jspecify.annotations.Nullable;

import lombok.NonNull;

/**
 * A reader over some text followed by the base64 of a byte source — {@code data:image/png;base64,} and
 * then the payload, handed out as it is read rather than assembled first.
 *
 * <p>
 * The text in front is text to hand out before the payload and nothing more: this reader does not parse
 * it and has no idea what a data URL is, so a caller that wants a different one, or none at all, says so.
 *
 * <p>
 * The payload is written in the standard alphabet, with padding and without line breaks, which is the
 * form a value inside JSON has to take. Reading is incremental: one chunk of the source is encoded at a
 * time, so a payload larger than memory can still be written, and the source is opened when the first
 * character is asked for rather than when this reader is created.
 *
 * <p>
 * One instance reads one string. A second reading of the same content is a second instance, and that is
 * what asks the source for a new stream. The source is closed once its content has been read out, and by
 * {@link #close()} as well; closing twice does nothing the second time.
 */
public class Base64Reader extends Reader {

    /**
     * How much of the source is read and encoded at a time, in bytes: a multiple of three, so that no
     * group of three is split across two chunks.
     */
    private static final int CHUNK = 3 * 1024;

    private final String prefix;

    private final InputStreamSupplier source;

    private final byte[] input = new byte[CHUNK];

    private @Nullable InputStream stream;

    private int prefixPosition;

    private String encoded = "";

    private int encodedPosition;

    private boolean sourceDone;

    private boolean closed;

    /**
     * A reader over the base64 of the source, with nothing in front of it.
     *
     * @param source where the bytes come from; never {@code null}
     */
    public Base64Reader(InputStreamSupplier source) {
        this(null, source);
    }

    /**
     * A reader over the given text followed by the base64 of the source.
     *
     * @param prefix the text to hand out first, or {@code null} for none
     * @param source where the bytes come from; never {@code null}
     */
    public Base64Reader(@Nullable String prefix, @NonNull InputStreamSupplier source) {
        this.prefix = prefix == null ? "" : prefix;
        this.source = source;
    }

    @Override
    public int read(@NonNull char[] target, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || length > target.length - offset) {
            throw new IndexOutOfBoundsException(
                    "offset " + offset + " and length " + length + " do not fit in a buffer of " + target.length);
        }
        if (length == 0) {
            return 0;
        }
        if (closed) {
            throw new IOException("this reader is closed");
        }
        if (prefixPosition < prefix.length()) {
            int count = Math.min(length, prefix.length() - prefixPosition);
            prefix.getChars(prefixPosition, prefixPosition + count, target, offset);
            prefixPosition += count;
            return count;
        }
        while (encodedPosition == encoded.length()) {
            if (sourceDone) {
                return -1;
            }
            fill();
        }
        int count = Math.min(length, encoded.length() - encodedPosition);
        encoded.getChars(encodedPosition, encodedPosition + count, target, offset);
        encodedPosition += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        closeStream();
    }

    /** Reads one chunk of the source and encodes it, or marks the source as read to its end. */
    private void fill() throws IOException {
        if (stream == null) {
            stream = source.get();
        }
        int count = stream.readNBytes(input, 0, input.length);
        if (count < input.length) {
            sourceDone = true;
            closeStream();
        }
        encoded = StandardCharsets.US_ASCII
                .decode(Base64.getEncoder().encode(ByteBuffer.wrap(input, 0, count)))
                .toString();
        encodedPosition = 0;
    }

    private void closeStream() throws IOException {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

}
