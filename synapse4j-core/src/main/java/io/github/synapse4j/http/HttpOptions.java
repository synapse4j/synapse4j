package io.github.synapse4j.http;

import java.time.Duration;
import java.util.Objects;

import lombok.Data;

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
public class HttpOptions {

    /**
     * The default {@link #bodyWriteMode}: the body is not gathered into memory, and an implementation
     * that cannot take a written body as it comes converts it on a thread of its own.
     */
    public static final String STREAMED = "streamed";

    /**
     * The other {@link #bodyWriteMode}: an implementation that cannot take a written body as it comes
     * may hold the whole body in memory instead, which is what a caller picks when they would rather
     * spend memory than a thread.
     */
    public static final String BUFFERED = "buffered";

    /**
     * How a request body reaches an implementation whose HTTP library cannot take a written body as it
     * comes.
     *
     * <p>
     * One of {@link #STREAMED} or {@link #BUFFERED}. An implementation whose library takes the body as
     * it comes — a synchronous client writing on the caller's thread — has nothing to convert and
     * ignores the setting: the values are about a conversion that may not be needed.
     *
     * <p>
     * A string rather than a closed type, like every value in this library that can grow: an
     * implementation may define a mode of its own. One that does not know the mode it is handed must
     * refuse the request rather than treat it as the default — a caller who asked for one thing must
     * not silently get another.
     */
    private String bodyWriteMode;

    /**
     * How long to wait for the response to start arriving (its headers), measured by the implementation
     * from when the request is sent. Does not bound reading the body — body stalls are the caller's or a
     * higher layer's concern.
     */
    private Duration responseTimeout;

    /**
     * The defaults every implementation starts from, written down once so that what this library does
     * when nobody configures anything is the same everywhere: {@link #STREAMED} bodies, and no response
     * timeout of its own — an implementation that sets none leaves that to its HTTP library.
     *
     * @return a new instance holding those defaults
     */
    public static HttpOptions defaults() {
        HttpOptions defaults = new HttpOptions();
        defaults.bodyWriteMode = STREAMED;
        return defaults;
    }

    /**
     * The options in effect for one request: what the request itself sets, and the implementation's
     * defaults for everything it does not.
     *
     * @param options  the options the request carries; may be {@code null}
     * @param defaults the implementation's own options; never {@code null}
     * @return a new instance holding the request's options with their gaps filled in from the
     *         defaults — never {@code defaults} itself or {@code options} itself, so the caller may
     *         change the answer without touching either
     */
    public static HttpOptions effective(HttpOptions options, HttpOptions defaults) {
        Objects.requireNonNull(defaults, "defaults must not be null");
        HttpOptions carried = options == null ? new HttpOptions() : options;
        HttpOptions effective = new HttpOptions();
        effective.bodyWriteMode = carried.bodyWriteMode != null ? carried.bodyWriteMode : defaults.bodyWriteMode;
        effective.responseTimeout = carried.responseTimeout != null ? carried.responseTimeout
                : defaults.responseTimeout;
        return effective;
    }

}
