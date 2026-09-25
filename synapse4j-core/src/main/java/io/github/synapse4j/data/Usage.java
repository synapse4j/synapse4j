package io.github.synapse4j.data;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * What a call consumed and produced, normalised so that a count means the same thing whoever
 * reported it.
 *
 * <p>
 * The counts are not passed through as the provider wrote them, because providers disagree about
 * what one name means: one counts every input token in its prompt count, another leaves the cached
 * ones out of it. Here {@link #inputTokens} is always the whole input, and the cached part is a
 * <em>child</em> bucket of it — never a sibling to be added on top.
 *
 * <p>
 * A count this library does not model — a cache write, an audio count, a billing unit that is not a
 * token at all — is one path in {@code getExtras()}, the same bag every other node keeps.
 *
 * <p>
 * Every count is optional: not every provider reports them, and a streaming one usually reports them
 * only in the final frame.
 */
@Getter
@Setter
@ToString
public class Usage {

    /** Every input token the request sent, cache reads and cache writes included. */
    private Integer inputTokens;

    /** Every token the model generated, reasoning tokens included. */
    private Integer outputTokens;

    /** The part of {@link #inputTokens} that came from the cache; already counted in it. */
    private Integer cachedInputTokens;

    /** Counts outside the model above, as paths: cache writes, audio, billing units, and so on. */
    private final ProviderExtras extras = new ProviderExtras();

}
