package io.github.synapse4j.util;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import lombok.extern.java.Log;

/**
 * A {@link Cache} over a {@link ConcurrentHashMap}, bounded: once it holds the given number of entries,
 * the next value to be stored starts over from an empty map.
 *
 * <p>
 * Dropping everything when full is crude on purpose. The keys here are a small, mostly fixed set, so
 * the bound is rarely reached — and when it is, the keys that go are ones a later ask was unlikely to
 * repeat, so little is lost. In exchange the cache stays lock-free: no access order to keep, no entry
 * to pick for eviction. The clear runs on the write path, where the size is touched anyway, and never
 * on a read.
 *
 * <p>
 * A clear that races a write may leave a few entries behind: the capacity is a bound, not an exact
 * count, and the next write past it clears again.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@Log
public class BoundedConcurrentCache<K, V> implements Cache<K, V> {

    /** The capacity a cache gets when none is given. */
    public static final int DEFAULT_CAPACITY = 8192;

    private final int capacity;

    private final ConcurrentHashMap<K, V> entries = new ConcurrentHashMap<>();

    /** Creates a cache with the {@linkplain #DEFAULT_CAPACITY default capacity}. */
    public BoundedConcurrentCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a cache that starts over once it holds the given number of entries.
     *
     * @param capacity the number of entries kept before the cache starts over; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public BoundedConcurrentCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> compute) {
        V cached = entries.get(key);
        if (cached != null) {
            return cached;
        }
        V computed = Objects.requireNonNull(compute.apply(key), "the compute function answered null");
        if (entries.size() >= capacity) {
            entries.clear();
            log.fine("The cache reached its capacity of " + capacity + " and was cleared");
        }
        entries.put(key, computed);
        return computed;
    }

}
