package com.taolesi.mcthread.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeltaCacheTest {

    @Test
    void hitWhenVersionStable() {
        DeltaCache<String, Integer> cache = new DeltaCache<>();
        AtomicLong version = new AtomicLong(1);
        AtomicInteger computes = new AtomicInteger();

        assertEquals(42, cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 42;
        }));
        assertEquals(42, cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 42;
        }));

        assertEquals(1, computes.get(), "second lookup must be served from cache");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void recomputeOnVersionChange() {
        DeltaCache<String, Integer> cache = new DeltaCache<>();
        AtomicLong version = new AtomicLong(1);
        AtomicInteger computes = new AtomicInteger();

        cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 1;
        });
        version.set(2);
        cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 2;
        });

        assertEquals(2, computes.get(), "version change must invalidate the entry");
        assertEquals(2, cache.misses());
    }

    @Test
    void markDirtyForcesRecompute() {
        DeltaCache<String, Integer> cache = new DeltaCache<>();
        AtomicLong version = new AtomicLong(1);
        AtomicInteger computes = new AtomicInteger();

        cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 1;
        });
        cache.markDirty("k");
        cache.getOrCompute("k", version::get, k -> {
            computes.incrementAndGet();
            return 1;
        });

        assertEquals(2, computes.get());
        assertEquals(0, cache.hits());
        assertEquals(2, cache.misses());
    }

    @Test
    void clearInvalidatesAll() {
        DeltaCache<String, Integer> cache = new DeltaCache<>();
        AtomicLong version = new AtomicLong(1);
        AtomicInteger computes = new AtomicInteger();

        cache.getOrCompute("a", version::get, k -> {
            computes.incrementAndGet();
            return 1;
        });
        cache.getOrCompute("b", version::get, k -> {
            computes.incrementAndGet();
            return 2;
        });
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
        cache.getOrCompute("a", version::get, k -> {
            computes.incrementAndGet();
            return 1;
        });
        assertEquals(3, computes.get());
    }
}
