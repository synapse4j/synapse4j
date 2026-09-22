package io.github.synapse4j.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

/**
 * The default {@link HttpResponse}: status, headers and body, each handed in by the transport that
 * produced it, plus the options that were in effect for the exchange — the last one only so that
 * {@link #sseEventStream()} can frame the events with the budget the call asked for.
 */
public class DefaultHttpResponse implements HttpResponse {

    /** The media type of a server-sent event stream, as the protocol spells it. */
    private static final String EVENT_STREAM = "text/event-stream";

    /** The HTTP status code, exactly as received. */
    @Getter
    @Setter
    private int statusCode;

    /** The response header lines, multiple values per name. Never {@code null}. */
    @NonNull
    @Getter
    private final Map<String, List<String>> headers = new LinkedHashMap<>();

    /** The body stream. Read on the caller's thread; closed by {@link #close()}. */
    @NonNull
    @Getter
    @Setter
    private InputStream body;

    /**
     * The options in effect for the exchange this response answers: what the request asked for, with
     * the transport's own defaults filling the gaps. The transport is the only place both are known,
     * and {@link HttpOptions#effective} is where they come together. Handed in, never read back — a
     * response has no business telling a caller what the request was sent with.
     *
     * <p>
     * Only an event stream needs it: {@link #sseEventStream()} frames its events with the budget this
     * carries, falling back to the library's default when it was handed none.
     */
    @Setter
    private HttpOptions options;

    /** The one event stream of this response, built on the first call and handed out after that. */
    private SseEventStream eventStream;

    @Override
    public synchronized SseEventStream sseEventStream() {
        if (eventStream == null && isEventStream()) {
            eventStream = new DefaultSseEventStream(body, frameBudget());
        }
        return eventStream;
    }

    /**
     * The frame budget the exchange ran under, or the library's own when the transport handed none
     * in — options never merged with its own carry no budget either, and a response has nothing to
     * read one from.
     */
    private int frameBudget() {
        Integer budget = options == null ? null : options.getMaxFrameBytes();
        return budget != null ? budget : HttpOptions.DEFAULT_MAX_FRAME_BYTES;
    }

    /**
     * Whether this response carries a {@code text/event-stream}. Header names are compared without
     * regard to case, since only some transports normalize them, and only the media type counts: the
     * parameters after it are the server's business — {@code text/event-stream; charset=utf-8} is
     * the shape providers send.
     */
    private boolean isEventStream() {
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            for (String value : header.getValue()) {
                if (EVENT_STREAM.equalsIgnoreCase(mediaTypeOf(value))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The media type of one header value: everything before its parameters, trimmed. */
    private static String mediaTypeOf(String value) {
        int parameters = value.indexOf(';');
        return (parameters < 0 ? value : value.substring(0, parameters)).trim();
    }

    /**
     * Releases the connection behind this response. A response that handed an event stream out
     * releases it through that stream, whose contract says closing it closes the body it reads —
     * and whatever else the stream holds is then its own business rather than this class's to
     * assume. A response that handed none out closes the body itself. Idempotent and safe to call
     * from any thread.
     */
    @Override
    public void close() throws IOException {
        SseEventStream stream = eventStream;
        if (stream != null) {
            stream.close();
            return;
        }
        if (body != null) {
            body.close();
        }
    }

}
