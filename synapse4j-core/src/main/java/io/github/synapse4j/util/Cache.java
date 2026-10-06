package io.github.synapse4j.util;

import java.util.function.Function;

/**
 * A computing cache: the value for a key, computed the first time it is asked for and answered from
 * the cache afterwards.
 *
 * <p>
 * One method is the whole use — a caller hands the key and how to compute its value, and the cache
 * decides whether to compute. Storage, eviction, and whether the compute is made atomic are the
 * implementation's business; a caller never reads and writes the cache itself.
 *
 * <p>
 * An instance is shared across threads, so {@link #get} may be called from several at once and an
 * implementation must be thread-safe. The value the compute function answers is stored as it is and
 * must not be {@code null}: a cache of nulls could not tell a stored absence from a missing entry.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface Cache<K, V> {

    /**
     * The value cached for the key, computing and storing it when it is not there yet.
     *
     * @param key     the key to look up; must not be {@code null}
     * @param compute how to compute the value when it is absent; must not be {@code null}, and must
     *                    answer a non-null value
     * @return the cached value, or the one just computed; never {@code null}
     */
    V get(K key, Function<? super K, ? extends V> compute);

}
