package io.github.synapse4j.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class BoundedConcurrentCacheTest {

    @Test
    void aValueIsComputedOnceAndAnsweredFromTheCacheAfter() {
        BoundedConcurrentCache<String, String> cache = new BoundedConcurrentCache<>();
        AtomicInteger computed = new AtomicInteger();

        String first = cache.get("k", key -> {
            computed.incrementAndGet();
            return key + "!";
        });
        String second = cache.get("k", key -> {
            computed.incrementAndGet();
            return key + "!";
        });

        assertEquals("k!", first);
        assertEquals("k!", second);
        assertEquals(1, computed.get());
    }

    @Test
    void storingPastTheCapacityStartsTheCacheOver() {
        BoundedConcurrentCache<String, String> cache = new BoundedConcurrentCache<>(2);

        cache.get("a", key -> "A");
        cache.get("b", key -> "B");
        // The third entry goes past the bound, so the cache starts over and "a" is gone.
        cache.get("c", key -> "C");

        AtomicInteger recomputed = new AtomicInteger();
        String again = cache.get("a", key -> {
            recomputed.incrementAndGet();
            return "A";
        });

        assertEquals("A", again);
        assertEquals(1, recomputed.get());
    }

}
