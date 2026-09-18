package io.github.synapse4j.data;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The well-known values for the finish reason of a {@link ChatResponse}.
 *
 * <p>
 * Like a role, a finish reason is a plain string rather than an {@code enum}, so a provider value
 * this library has no neutral equivalent for stays readable instead of being forced into a box. An
 * adapter maps the values it can onto these constants and passes the rest through unchanged.
 *
 * <p>
 * A holder of constants rather than a data class: it is final, and it cannot be instantiated.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChatFinishReason {

    /** The model finished its answer on its own. */
    public static final String STOP = "stop";

    /** The output limit was reached. */
    public static final String LENGTH = "length";

    /** The model stopped in order to call tools. */
    public static final String TOOL_CALLS = "tool_calls";

    /** The provider's safety filter cut the answer short. */
    public static final String CONTENT_FILTER = "content_filter";

}
