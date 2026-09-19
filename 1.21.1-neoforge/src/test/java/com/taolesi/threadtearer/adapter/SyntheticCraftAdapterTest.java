package com.taolesi.threadtearer.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticCraftAdapterTest {

    @Test
    void computePlanIsDeterministic() {
        long a = SyntheticCraftAdapter.computePlan(1000, 50);
        long b = SyntheticCraftAdapter.computePlan(1000, 50);
        assertEquals(a, b, "pure compute must be deterministic");
        assertTrue(a > 0);
    }

    @Test
    void planIsCachedUntilVersionChanges() {
        SyntheticCraftAdapter adapter = new SyntheticCraftAdapter();
        long p1 = adapter.plan(100, 10);
        long p2 = adapter.plan(100, 10);
        assertEquals(p1, p2);
        assertEquals(1, adapter.cacheHits());
        assertEquals(1, adapter.cacheMisses());

        adapter.bumpStateVersion();
        long p3 = adapter.plan(100, 10);
        assertEquals(p1, p3);
        assertEquals(1, adapter.cacheHits());
        assertEquals(2, adapter.cacheMisses(), "version bump must invalidate the delta cache");
    }

}
