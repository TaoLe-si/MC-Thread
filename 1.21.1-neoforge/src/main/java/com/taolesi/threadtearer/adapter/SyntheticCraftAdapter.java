package com.taolesi.threadtearer.adapter;

import com.taolesi.threadtearer.api.DomainAdapter;
import com.taolesi.threadtearer.api.MCTRuntime;
import com.taolesi.threadtearer.runtime.DeltaCache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference adapter for the "synthetic craft planning" domain.
 *
 * <p>No external mod dependency: it simulates an AE2-like pure-compute
 * planning workload so the Snapshot → Compute → Validate → Commit pipeline
 * and Delta caching can be exercised and benchmarked end-to-end in any
 * environment ({@code /threadtearer demo run}).
 */
public final class SyntheticCraftAdapter implements DomainAdapter {

    public static final String DOMAIN_ID = "synthetic-craft";
    private static final long MOD = 1_000_000_007L;

    private final DeltaCache<String, Long> planCache = new DeltaCache<>();
    private final AtomicLong stateVersion = new AtomicLong();

    @Override
    public String domainId() {
        return DOMAIN_ID;
    }

    @Override
    public String targetModId() {
        return "";
    }

    @Override
    public void onAttach(MCTRuntime runtime) {
    }

    @Override
    public void onDetach() {
    }

    /** Current state version; bump it to simulate a storage/pattern change. */
    public long stateVersion() {
        return stateVersion.get();
    }

    public long bumpStateVersion() {
        return stateVersion.incrementAndGet();
    }

    /** Delta-cached plan lookup: recomputes only when the state version changed. */
    public long plan(int recipes, int iterations) {
        return planCache.getOrCompute("plan", stateVersion::get,
                key -> computePlan(recipes, iterations));
    }

    public long cacheSize() {
        return planCache.size();
    }

    public long cacheHits() {
        return planCache.hits();
    }

    public long cacheMisses() {
        return planCache.misses();
    }

    /** Pure compute workload simulating craft planning (deterministic, side-effect free). */
    public static long computePlan(int recipes, int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            for (int r = 1; r <= recipes; r++) {
                acc = (acc + (long) r * r * 31L) % MOD;
            }
        }
        return acc;
    }

}
