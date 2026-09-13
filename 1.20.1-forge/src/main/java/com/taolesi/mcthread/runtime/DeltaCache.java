package com.taolesi.mcthread.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Delta/增量缓存（Rule-010：最好的计算是不发生的计算）。
 *
 * <p>Entries are keyed by state version; when the underlying state version is
 * unchanged the cached result is reused instead of recomputed (O(Δ) rather than
 * O(N)). {@link #markDirty(Object)} forces invalidation even without a version
 * change (external dirty signals).
 *
 * <p>Note: concurrent misses for the same key may compute duplicates; the
 * compute function must therefore be pure and side-effect free.
 */
public final class DeltaCache<K, V> {

    private record Entry<V>(long version, V value) {
    }

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    /** Returns the cached value if the version matches, otherwise recomputes and caches. */
    public V getOrCompute(K key, LongSupplier versionSupplier, Function<K, V> compute) {
        long version = versionSupplier.getAsLong();
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.version() == version) {
            hits.increment();
            return entry.value();
        }
        V value = compute.apply(key);
        entries.put(key, new Entry<>(version, value));
        misses.increment();
        return value;
    }

    /** Invalidates one key. */
    public void markDirty(K key) {
        entries.remove(key);
    }

    /** Invalidates everything. */
    public void markDirtyAll() {
        entries.clear();
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }
}
