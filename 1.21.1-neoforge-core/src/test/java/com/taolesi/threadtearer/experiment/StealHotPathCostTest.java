package com.taolesi.threadtearer.experiment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why {@code steal()} lost its per-call timing.
 *
 * <p>The in-game readout showed 3.35M calls in 100 ticks (~33k/tick while
 * chunks load, since every light/section/block update of every mod passes
 * through). This measures what the instrumentation alone cost per call, so the
 * decision is on record rather than a hunch: if {@code nanoTime + LongAdder}
 * dominates a 0.36 µs hot path, timing every call is the bug.
 */
class StealHotPathCostTest {

    private static final int ITERS = 20_000_000;

    /** The shape {@code steal()} used to have. */
    private static boolean instrumented(Runnable vanilla) {
        long t0 = System.nanoTime();
        try {
            return false;
        } finally {
            PhaseTimingsLocal.STEAL_CALLS.increment();
            PhaseTimingsLocal.STEAL_NANOS.add(System.nanoTime() - t0);
        }
    }

    /** The shape it has now. */
    private static boolean plain(Runnable vanilla) {
        return false;
    }

    /** Local copies so the benchmark does not depend on the production class. */
    private static final class PhaseTimingsLocal {
        static final LongAdder STEAL_CALLS = new LongAdder();
        static final LongAdder STEAL_NANOS = new LongAdder();
    }

    @Test
    void instrumentationCostPerCall() {
        Runnable noop = () -> { };

        for (int i = 0; i < 2_000_000; i++) {
            instrumented(noop);
            plain(noop);
        }

        long t = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            instrumented(noop);
        }
        double instrumentedNs = (System.nanoTime() - t) / (double) ITERS;

        t = System.nanoTime();
        for (int i = 0; i < ITERS; i++) {
            plain(noop);
        }
        double plainNs = (System.nanoTime() - t) / (double) ITERS;

        System.out.printf("[steal] instrumented: %.1f ns/call%n", instrumentedNs);
        System.out.printf("[steal] plain:        %.1f ns/call%n", plainNs);
        System.out.printf("[steal] overhead:     %.1f ns/call (%.1fx)%n",
                instrumentedNs - plainNs, instrumentedNs / Math.max(plainNs, 0.1));
        System.out.printf("[steal] => at 33k calls/tick that overhead was %.2f ms/tick%n",
                (instrumentedNs - plainNs) * 33_000 / 1_000_000.0);
        System.out.printf("[steal] => after removal, 33k calls/tick costs %.2f ms/tick%n",
                plainNs * 33_000 / 1_000_000.0);

        assertTrue(instrumentedNs > plainNs,
                "instrumentation must cost something, otherwise this test is measuring nothing");
    }
}
