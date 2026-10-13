package io.github.synapse4j.json;

import org.jspecify.annotations.Nullable;

/**
 * An instance stands for another value: a writer that meets one writes the value {@link #toJson()}
 * yields in its place, so the instance itself never reaches the sink.
 *
 * <p>
 * An instance may be asked for its value more than once, and has to answer every time: what a writer
 * writes can be written again, so a source built over something that can be read only once — an
 * iterator, a stream — has to buffer it rather than hand the one-shot thing on.
 *
 * <p>
 * What is yielded may itself be one of these, so a structure is deferred a level at a time, and what
 * has not been written yet has not been built yet. That is what lets a payload larger than the writer
 * needs to hold still go out whole.
 */
@FunctionalInterface
public interface JsonSource {

    /**
     * The value this instance stands for.
     *
     * @return the value; may be {@code null}, and may itself be one of these
     */
    @Nullable
    Object toJson();

    /**
     * The given source, as one of these — so a lambda can be written where a plain value is expected.
     *
     * @param source the source; never {@code null}
     * @return that source; never {@code null}
     */
    static JsonSource of(JsonSource source) {
        return source;
    }

}
