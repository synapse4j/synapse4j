package io.github.synapse4j.http;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import io.github.synapse4j.data.Effective;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * The HTTP-level settings a request can carry, and the ones an implementation falls back to.
 *
 * <p>
 * Every field is nullable, and {@code null} means "no opinion here": a request leaves what it does not
 * care about to the implementation's own instance of this type, and {@link #effective} is where the two
 * are put together. A field that could not say "not set" would force a caller to state a value they have
 * no opinion about, and an implementation could not tell the two apart.
 *
 * <p>
 * Nothing here is about a particular HTTP library. An implementation takes what it understands and, when
 * a request asks for something it cannot do, says so rather than silently doing something else.
 */
@Data
@NoArgsConstructor
public class HttpOptions implements Effective<HttpOptions> {

    /**
     * How a request body reaches the implementation: one of the modes {@link BodyWriteMode} defines, or
     * a mode of the caller's own outside that set — which this library defines nothing for, and honors
     * only where the caller's implementation honors it.
     *
     * <p>
     * A string rather than the enum, like every value in this library that can grow: a mode of one's own
     * is a value a closed type could not carry. An implementation that does not know the mode it is
     * handed must refuse the request rather than treat it as the default — a caller who asked for one
     * thing must not silently get another. {@link BodyWriteMode#AUTO} is the one every implementation
     * must take: it asks for the implementation's own choice, so refusing it would refuse the default.
     */
    private @Nullable String bodyWriteMode;

    /**
     * How long to wait for the response to start arriving (its headers), measured by the implementation
     * from when the request is sent. Does not bound reading the body — body stalls are the caller's or a
     * higher layer's concern — though a transport may apply it more coarsely than that, which it says
     * where it does.
     */
    private @Nullable Duration responseTimeout;

    /**
     * The most bytes one server-sent event frame may accumulate before the blank line that
     * dispatches it. A frame is only complete once that blank line arrives, so without a cap a
     * server that keeps sending {@code data:} lines would pin an ever-growing buffer. The count
     * is in the wire's terms — a character costs its UTF-8 length — and it covers every line of
     * the frame, comments included.
     *
     * <p>
     * Enforced where the frames are read, above any particular HTTP implementation, so the same
     * budget holds for every transport. Exceeding it fails the read; it never truncates, since
     * half a frame is worse than none. The default {@link #defaults()} carries is generous enough
     * for the payloads providers actually stream — tool calls with big arguments, reasoning
     * traces — and small enough that a server which never dispatches a frame cannot pin an
     * unbounded buffer.
     */
    private @Nullable Integer maxFrameBytes;

    /**
     * The frame budget nothing states otherwise: what {@link #defaults()} carries, and what a
     * response falls back to when it was handed no options to read a budget from. Written down once
     * so the number lives in a single place.
     */
    static final int DEFAULT_MAX_FRAME_BYTES = 256 * 1024;

    public HttpOptions(HttpOptions other) {
        this.bodyWriteMode = other.bodyWriteMode;
        this.responseTimeout = other.responseTimeout;
        this.maxFrameBytes = other.maxFrameBytes;
    }

    /**
     * The defaults every implementation starts from, written down once so that what this library does
     * when nobody configures anything is the same everywhere.
     *
     * @return a new instance holding those defaults
     */
    public static HttpOptions defaults() {
        HttpOptions defaults = new HttpOptions();
        defaults.bodyWriteMode = BodyWriteMode.AUTO.value();
        defaults.maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
        return defaults;
    }

    /**
     * The options in effect when the carried ones may be absent: {@code carried} over {@code base},
     * or {@code base}'s own values when nothing is carried. The convenience for the common case where
     * a request's options are optional and the implementation's are not.
     *
     * @param carried the options a request carries, or {@code null} for none
     * @param base    the options to fall back to; never {@code null}
     * @return the effective options; never {@code null}
     */
    public static HttpOptions effective(@Nullable HttpOptions carried, @NonNull HttpOptions base) {
        return (carried == null ? new HttpOptions() : carried).effective(base);
    }

    @Override
    public HttpOptions copy() {
        return new HttpOptions(this);
    }

    @Override
    public void fillFrom(HttpOptions other) {
        if (bodyWriteMode == null) {
            bodyWriteMode = other.bodyWriteMode;
        }
        if (responseTimeout == null) {
            responseTimeout = other.responseTimeout;
        }
        if (maxFrameBytes == null) {
            maxFrameBytes = other.maxFrameBytes;
        }
    }

}
