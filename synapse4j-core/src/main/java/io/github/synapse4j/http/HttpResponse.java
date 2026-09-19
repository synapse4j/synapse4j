package io.github.synapse4j.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

/**
 * One HTTP response: status, headers, and the body as a stream.
 *
 * <p>
 * The body is always a stream, for every request — streaming and non-streaming callers read the
 * same type, and buffering is a choice the caller makes by reading to the end, not a property of
 * the response. The caller's thread does the reading; {@link #close()} releases the connection and
 * cancels an in-flight body, and must be safe to call from any thread.
 *
 * <p>
 * The status is whatever the server returned. This type has no opinion about 4xx or 5xx and no
 * error subtype: whether a status means failure belongs to the layer that knows what was being
 * asked.
 */
@Getter
@Setter
@NoArgsConstructor
public class HttpResponse implements AutoCloseable {

    /** The HTTP status code, exactly as received. */
    private int statusCode;

    /** The response header lines, multiple values per name. Never {@code null}. */
    @NonNull
    private Map<String, List<String>> headers = new LinkedHashMap<>();

    /** The body stream. Read on the caller's thread; closed by {@link #close()}. */
    @NonNull
    private InputStream body;

    /**
     * Releases the connection backing this response, cancelling the body if it is still in flight.
     * Idempotent and safe to call from any thread, including without having read anything.
     */
    @Override
    public void close() throws IOException {
        if (body != null) {
            body.close();
        }
    }

}
