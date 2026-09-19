package com.taolesi.threadtearer.experiment;

import com.taolesi.threadtearer.monitor.PhaseTimings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityTickLockTest {

    @Test
    void serializesTicksOnTheSameBlockEntity() throws Exception {
        FakeLock owner = new FakeLock();
        int[] value = {0};
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int t = 0; t < 2; t++) {
                pool.execute(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        InteractionRelocator.runLockedBlockEntityTick(owner, () -> value[0]++);
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "both tickers should finish");
            assertEquals(20_000, value[0], "lost updates mean the same block entity ticked in parallel");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The eject split in the Mek addon relies on this lock working in both
     * directions: a compute worker holds it for the recipe half of a tick while
     * the server thread waits for it to run the deferred eject half. If the lock
     * ever stopped excluding across those two threads, the eject would read the
     * machine's slots while the recipe half was writing them.
     */
    @Test
    void deferredHalfCannotOverlapTheTickerOnAnotherThread() throws Exception {
        FakeLock owner = new FakeLock();
        AtomicBoolean tickerInside = new AtomicBoolean();
        AtomicBoolean overlap = new AtomicBoolean();
        AtomicLong tickerRuns = new AtomicLong();
        AtomicLong deferredRuns = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread ticker = new Thread(() -> {
            try {
                for (int i = 0; i < 5_000; i++) {
                    InteractionRelocator.runLockedBlockEntityTick(owner, () -> {
                        tickerInside.set(true);
                        tickerRuns.incrementAndGet();
                        tickerInside.set(false);
                    });
                    started.countDown();
                }
            } finally {
                done.countDown();
            }
        }, "test-compute-worker");

        Thread server = new Thread(() -> {
            try {
                started.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 5_000; i++) {
                    InteractionRelocator.runLockedBlockEntityTick(owner, () -> {
                        if (tickerInside.get()) {
                            overlap.set(true);
                        }
                        deferredRuns.incrementAndGet();
                    });
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, "test-server");

        ticker.setDaemon(true);
        server.setDaemon(true);
        ticker.start();
        server.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "both halves should finish");
        assertFalse(overlap.get(), "the deferred half ran while the ticker was inside its half");
        assertEquals(5_000, tickerRuns.get());
        assertEquals(5_000, deferredRuns.get(), "a deferred half was dropped");
    }

    /**
     * The lock-wait counter exists to answer "is the server thread stalling on a
     * worker" — the failure mode this design introduces. It must stay quiet for
     * uncontended acquisitions, or the readout is noise instead of a signal.
     */
    @Test
    void uncontendedAcquisitionsAreNotCountedAsLockWaits() {
        FakeLock owner = new FakeLock();
        long before = PhaseTimings.LOCK_WAITS.sum();
        for (int i = 0; i < 10_000; i++) {
            InteractionRelocator.runLockedBlockEntityTick(owner, () -> {
            });
        }
        assertEquals(before, PhaseTimings.LOCK_WAITS.sum(),
                "uncontended lock acquisitions must not be reported as waits");
    }

    private static final class FakeLock implements BlockEntityTickLock {
        private final Object lock = new Object();

        @Override
        public Object threadtearer$tickLock() {
            return lock;
        }
    }
}
