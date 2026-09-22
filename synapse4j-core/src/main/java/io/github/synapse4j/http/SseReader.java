package io.github.synapse4j.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

import io.github.synapse4j.exception.SynapseException;
import io.github.synapse4j.exception.SynapseIOException;

/**
 * Reads a {@code text/event-stream} body as a sequence of {@link SseEvent} frames.
 *
 * <p>
 * The format is cut by its own rules, not by a protocol's: lines end with CR, LF or CRLF, a line
 * starting with {@code :} is a comment, a blank line dispatches the frame that accumulated before
 * it, and a frame with no {@code data:} line at all is not an event and is skipped — the same
 * rule that makes a keep-alive comment free. Field names other than {@code event} and {@code data}
 * are ignored here; a protocol that needs them reads them from the payload or not at all. One
 * UTF-8 byte order mark at the very start, which the format's grammar permits once, is dropped.
 *
 * <p>
 * The event name travels exactly as the wire carries it: {@code null} when a frame names none,
 * the empty string when it names an empty one. The specification's default of {@code "message"}
 * is a browser EventSource concept; this reader stays faithful to what arrives and leaves any
 * defaulting to the consumer.
 *
 * <p>
 * One frame may buffer no more than {@code maxFrameBytes} bytes: a frame completes only when a
 * blank line arrives, so without that budget a server that keeps sending lines would pin an
 * ever-growing buffer. The count is the wire's — a character costs its UTF-8 length, so multibyte
 * payloads spend more of it — and it covers every line of the frame, comments included. Crossing
 * the budget fails the read; it never truncates, since half a frame is worse than none.
 *
 * <p>
 * Reading is lazy and blocking: {@link #hasNext()} waits for the next frame to arrive on the
 * calling thread, so a caller that stops pulling stops the provider — the same backpressure the
 * body stream itself has. The frames arrive over one body, and the caller owns that body: closing
 * this reader closes it, which is how a streaming response is cancelled. Nothing touches the
 * body before the first pull, so constructing a reader never starts a conversation.
 *
 * <p>
 * One instance reads one body, on one thread. A failure of the source is a
 * {@link SynapseIOException} whose cause is the original {@link IOException}.
 */
public class SseReader implements Iterator<SseEvent>, AutoCloseable {

    private final PushbackInputStream source;

    private final BufferedReader lines;

    /** How many bytes one frame may accumulate before the read fails. */
    private final int maxFrameBytes;

    /** Bytes accumulated for the frame in progress; every blank line starts the next one back at zero. */
    private int frameBytes;

    /** Whether the stream's first bytes have been peeked at for a BOM. */
    private boolean bomChecked;

    private SseEvent pending;

    private boolean finished;

    /**
     * Reads the given body as UTF-8, the encoding every mainstream provider streams. The body is
     * not touched until the first frame is asked for.
     *
     * @param body          the response body; never {@code null}
     * @param maxFrameBytes the most bytes one frame may accumulate before the blank line that
     *                          dispatches it; must be positive
     */
    public SseReader(InputStream body, int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        this.source = new PushbackInputStream(body, 3);
        this.lines = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
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
        SseEvent event = pending;
        pending = null;
        return event;
    }

    /**
     * Closes the body this reader was given. Idempotent, and the reason a streaming response ends
     * early when a caller closes it.
     */
    @Override
    public void close() {
        try {
            lines.close();
        } catch (IOException failure) {
            throw new SynapseIOException("Closing the event stream failed", failure);
        }
    }

    /** Reads lines until one frame is complete, or the body ends. */
    private SseEvent readFrame() {
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

    private String readLine() {
        skipLeadingBom();
        try {
            return lines.readLine();
        } catch (IOException failure) {
            throw new SynapseIOException("Reading the event stream failed", failure);
        }
    }

    /**
     * The format's grammar begins with an optional BOM, and decoding is supposed to drop it;
     * charset decoding alone would keep it as a stray character glued to the first line's field
     * name. The first three bytes are peeked at before the first line is read, and only a complete
     * {@code EF BB BF} is consumed — anything else, including a truncated sequence at the start
     * of a very short body, is pushed back unread. Deferred to the first pull so that constructing
     * a reader never touches the body.
     */
    private void skipLeadingBom() {
        if (bomChecked) {
            return;
        }
        bomChecked = true;
        byte[] head = new byte[3];
        try {
            int read = 0;
            while (read < 3) {
                int count = source.read(head, read, 3 - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            boolean bom = read == 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
                    && (head[2] & 0xFF) == 0xBF;
            source.unread(head, 0, bom ? 0 : read);
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
