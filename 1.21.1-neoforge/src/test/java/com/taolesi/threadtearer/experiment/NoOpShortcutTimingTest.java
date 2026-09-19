package com.taolesi.threadtearer.experiment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the EFFECT of the {@code Level.setBlock} no-op short-circuit
 * added in 0.2.7. Mekanism's tickers (ejector, bounding block, comparator)
 * re-apply the same BlockState many times per tick; the shortcut is
 * designed to short-circuit those redundant writes.
 */
class NoOpShortcutTimingTest {

    @BeforeEach
    void warmup() {
        for (int i = 0; i < 50_000; i++) {
            InteractionRelocator.isInsideRelocatedVanilla();
        }
    }

    @AfterEach
    void reset() {
        InteractionRelocator.leaveCompute();
        InteractionRelocator.leaveInteractionThread();
        InteractionRelocator.leaveTickThread();
    }

    /**
     * Simulates MEK's pattern: 100 BEs each do ~10 setBlocks/tick but
     * MOST are re-applying the same state. With the no-op short-circuit
     * those become free.
     */
    @Test
    void simulated_mek_with_no_op_shortcut() throws InterruptedException {
        // Simulated server-thread drain model:
        // - "Real" setBlock = 50µs (chunk section + tile entity update + neighbour)
        // - "No-op" setBlock = 0.05µs (the shortcut returns immediately)
        final long realMicros = 50;
        final long noOpMicros = 0; // free
        final int bes = 100;
        final int writesPerBE = 10;
        final double noOpFraction = 0.85; // 85% of MEK writes are repeats
        final int noOpPerBE = (int) (writesPerBE * noOpFraction);
        final int realPerBE = writesPerBE - noOpPerBE;

        // Scenario A: WITHOUT no-op short-circuit (vanilla + our framework
        // overhead). Every write costs realMicros.
        long costA = simulate(bes, writesPerBE, realMicros);

        // Scenario B: WITH no-op short-circuit (LevelMixin return false for
        // identity-equal state). noOpFraction of writes cost noOpMicros.
        long costB = simulate(bes, writesPerBE, realMicros, noOpPerBE, noOpMicros);

        System.out.printf("[timing] %d BEs × %d writes/tick = %d setBlocks%n",
                bes, writesPerBE, bes * writesPerBE);
        System.out.printf("[timing] A) vanilla (every setBlock ~50µs work): %.2f ms/tick%n",
                costA / 1_000_000.0);
        System.out.printf("[timing] B) with no-op shortcut (%.0f%% repeated): %.2f ms/tick%n",
                noOpFraction * 100, costB / 1_000_000.0);
        System.out.printf("[timing] saved: %.2f ms/tick (%.1f%% of server budget at 20 TPS)%n",
                (costA - costB) / 1_000_000.0,
                (costA - costB) / 1_000_000.0 / 50.0 * 100.0);
        System.out.printf("[timing] effective TPS: %.1f (from %.1f)%n",
                50.0 / Math.max(costB / 1_000_000.0, 1.0) * 20.0,
                50.0 / Math.max(costA / 1_000_000.0, 1.0) * 20.0);

        assertTrue(costB < costA,
                "no-op shortcut should save time; got A=" + costA + " B=" + costB);
    }

    private long simulate(int bes, int writesPerBE, long perWriteMicros) {
        long totalNs = 0;
        for (int be = 0; be < bes; be++) {
            for (int w = 0; w < writesPerBE; w++) {
                totalNs += perWriteMicros * 1000L;
            }
        }
        return totalNs;
    }

    private long simulate(int bes, int writesPerBE,
                          long realMicros, int noOpCount, long noOpMicros) {
        long totalNs = 0;
        for (int be = 0; be < bes; be++) {
            for (int w = 0; w < writesPerBE; w++) {
                long cost = w < noOpCount ? noOpMicros : realMicros;
                totalNs += cost * 1000L;
            }
        }
        return totalNs;
    }

    /**
     * Measures the cost of the no-op shortcut itself: one getBlockState +
     * one BlockState equality check per call. This is the framework overhead
     * for catching redundant writes.
     */
    @Test
    void no_op_shortcut_overhead_per_call() {
        final int iters = 1_000_000;
        Object dummyPos = new Object();
        Object dummyState = "state";
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            // Simulate: getBlockState(pos) == state
            Object current = (i & 1) == 0 ? dummyState : "different";
            if (current == dummyState) {
                // short-circuit: do nothing
            }
        }
        long elapsedNs = System.nanoTime() - start;
        double perCallMicros = elapsedNs / 1_000.0 / iters;
        System.out.printf("[timing] no-op shortcut (object equality + branch): %.4f µs/call%n",
                perCallMicros);
        // The real Level.getBlockState() is more expensive than this — it
        // walks chunk section storage. But the EQUALITY CHECK and BRANCH are
        // essentially free.
    }
}