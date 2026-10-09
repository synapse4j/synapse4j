package io.github.synapse4j.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * The default {@link SseEventStream}: reads the body as UTF-8, the encoding every mainstream
 * provider streams, and caps how much one frame may accumulate.
 *
 * <p>
 * One frame may buffer no more than {@code maxFrameBytes} bytes: a frame completes only when a
 * blank line arrives, so without that budget a server that keeps sending lines would pin an
 * ever-growing buffer. The count is the wire's — a byte arrives as a byte, so multibyte payloads
 * spend more of it — it covers the lines of the frame with their terminators left out, comments
 * included, and it is checked as the bytes arrive: a line whose newline never comes fails at the
 * budget rather than after paying for more. Crossing it fails the read; it never truncates, since
 * half a frame is worse than none.
 *
 * <p>
 * The body is not touched before the first pull, so constructing a reader never starts a
 * conversation.
 */
public class DefaultSseEventStream implements SseEventStream {

    /** The bytes a UTF-8 byte-order mark occupies; the first fill stops there to look for one. */
    private static final int BOM_LENGTH = 3;

    private final InputStream source;

    /** How many bytes one frame may accumulate before the read fails. */
    private final int maxFrameBytes;

    /** Bytes accumulated for the frame in progress; every blank line starts the next one back at zero. */
    private int frameBytes;

    /** Whether the body has been read from at all; the first fill is the one that looks for a BOM. */
    private boolean started;

    /**
     * Bytes read ahead from the body, so a line is assembled from a fill rather than a read per
     * byte. Owned by the reading thread — {@link #close()} marks the stream finished and reaches
     * only {@link #source}, never the buffer, which is what unblocks a reader parked on a silent
     * provider.
     */
    private final byte[] buffer = new byte[8192];

    private int position;

    private int limit;

    /** A CR ended the last line: an LF arriving first is its partner, not the next line's. */
    private boolean skipLf;

    /** The line being assembled, decoded once its terminator arrives. */
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    /** Written and read on the consuming thread alone: {@link #close()} never touches it. */
    private @Nullable SseEvent pending;

    /**
     * Whether the stream is over — the body ended, or {@link #close()} was called. Volatile because
     * the close may come from another thread, and a read that starts after it has to answer "over"
     * rather than touch the closed body. It cannot call back a read already under way: that one is
     * unblocked by {@link #close()} closing the body, and surfaces as a failed read.
     */
    private volatile boolean finished;

    /**
     * Reads the given body as UTF-8, the encoding every mainstream provider streams. The body is
     * not touched until the first frame is asked for.
     *
     * @param body          the response body; never {@code null}
     * @param maxFrameBytes the most bytes one frame may accumulate before the blank line that
     *                          dispatches it; must be positive
     */
    public DefaultSseEventStream(InputStream body, int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        this.source = body;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public boolean hasNext() {
        if (pending != null) {
            return true;
        }
        if (finished) {
            return false;
        }
        pending = readFrame();
        return pending != null;
    }

    @Override
    public SseEvent next() {
        if (!hasNext()) {
            throw new NoSuchElementException("the event stream is over");
        }
        SseEvent event = Objects.requireNonNull(pending, "hasNext answered true, so an event is pending");
        pending = null;
        return event;
    }

    /**
     * Closes the body this stream was given — directly, through no reader, so a thread parked on
     * a silent provider is unblocked by the close rather than left holding a lock the reader
     * wants. Idempotent, and the reason a streaming response ends early when a caller closes it:
     * nothing further is read once this returns, though a frame already read is still handed out —
     * a {@code hasNext()} that answered true is a promise {@code next()} keeps. A failure of the
     * close is the transport's own, so it travels as an {@link IOException} rather than being
     * wrapped.
     */
    @Override
    public void close() throws IOException {
        finished = true;
        source.close();
    }

    /**
     * Reads lines until one frame is complete.
     *
     * @return the frame, or {@code null} when the body ends before one is complete
     */
    private @Nullable SseEvent readFrame() {
        StringBuilder data = new StringBuilder();
        String event = null;
        while (true) {
            String line = readLine();
            if (line == null) {
                // The body ended mid-frame. The format discards an incomplete frame: what was
                // buffered is not an event, since no blank line ever dispatched it.
                finished = true;
                return null;
            }
            if (line.isEmpty()) {
                // The frame in progress is over, dispatched or skipped; what arrives next belongs
                // to a new one and gets the budget back.
                frameBytes = 0;
                if (data.length() == 0) {
                    // A blank line with nothing buffered is spacing, not an event.
                    event = null;
                    continue;
                }
                data.setLength(data.length() - 1); // The joining newline is not part of the value.
                return new SseEvent(event, data.toString());
            }
            frameBytes += utf8Length(line);
            if (frameBytes > maxFrameBytes) {
                throw new SynapseException("one event frame exceeded the " + maxFrameBytes + " byte budget");
            }
            if (line.charAt(0) == ':') {
                continue; // A comment, the shape keep-alives take.
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : withoutOneLeadingSpace(line.substring(colon + 1));
            switch (field) {
                case "event" -> event = value;
                case "data" -> data.append(value).append('\n');
                default -> {
                    // id, retry and anything else a protocol invents: not this reader's business.
                }
            }
        }
    }

    /**
     * One line, its terminator neither in the text nor in the budget: LF, CR and CRLF all end
     * one, and the LF of a CRLF is swallowed where the next line begins. The budget is checked
     * as the bytes arrive — a line whose newline never comes fails at the budget instead of
     * growing past it — and a line cut short by the end of the body comes back as it stands,
     * the way a reader over buffered text has always answered.
     *
     * @return the line without its terminator, or {@code null} at the end of the body
     */
    private @Nullable String readLine() {
        line.reset();
        int next = nextByte();
        if (skipLf) {
            skipLf = false;
            if (next == '\n') {
                next = nextByte();
            }
        }
        if (next < 0) {
            return null;
        }
        while (true) {
            if (next == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            if (next == '\r') {
                skipLf = true;
                return line.toString(StandardCharsets.UTF_8);
            }
            if (frameBytes + line.size() + 1 > maxFrameBytes) {
                throw new SynapseException("one event frame exceeded the " + maxFrameBytes + " byte budget");
            }
            line.write(next);
            next = nextByte();
            if (next < 0) {
                // The body ended mid-line: what stands comes back as the line it is, and the
                // next call answers null — the frame it belongs to is then discarded incomplete.
                return line.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /** The body's next byte, {@code -1} at its end. */
    private int nextByte() {
        if (position >= limit && !fill()) {
            return -1;
        }
        return buffer[position++] & 0xFF;
    }

    /**
     * Refills the buffer, answering whether it holds a byte: the first fill is the one that looks for a
     * BOM, and every later one takes whatever the body has.
     *
     * @return whether the buffer holds a byte
     */
    private boolean fill() {
        position = 0;
        limit = started ? read(buffer, 0, buffer.length) : firstFill();
        started = true;
        return position < limit;
    }

    /**
     * Fills the front of the buffer with whatever the body starts with, stopping at the three bytes a
     * BOM would occupy: the format's grammar begins with an optional mark and decoding is supposed to
     * drop it, where charset decoding alone would keep it glued to the first line's field name. Only a
     * complete {@code EF BB BF} is read past — it is not text, so what follows becomes the first line's
     * start — and anything else, a truncated sequence at the start of a very short body included, stays
     * as the line it begins.
     *
     * @return how many bytes the buffer holds; {@code -1} when the body is empty
     */
    private int firstFill() {
        int read = 0;
        while (read < BOM_LENGTH) {
            int count = read(buffer, read, BOM_LENGTH - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        if (read == BOM_LENGTH && (buffer[0] & 0xFF) == 0xEF && (buffer[1] & 0xFF) == 0xBB
                && (buffer[2] & 0xFF) == 0xBF) {
            return read(buffer, 0, buffer.length);
        }
        return read == 0 ? -1 : read;
    }

    /**
     * The body's next bytes into the given place, an {@link IOException} from the transport travelling
     * as the library's own rather than being left raw.
     */
    private int read(byte[] sink, int offset, int length) {
        try {
            return source.read(sink, offset, length);
        } catch (IOException failure) {
            throw new SynapseIOException("Reading the event stream failed", failure);
        }
    }

    /**
     * The budget is counted in bytes the way they go on the wire, so a character costs its UTF-8
     * length — one for ASCII, more for everything else.
     */
    private static int utf8Length(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            int codePoint = text.codePointAt(i);
            length += codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            i += Character.charCount(codePoint) - 1;
        }
        return length;
    }

    /** The format strips one leading space after the colon, and only one. */
    private static String withoutOneLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

}
