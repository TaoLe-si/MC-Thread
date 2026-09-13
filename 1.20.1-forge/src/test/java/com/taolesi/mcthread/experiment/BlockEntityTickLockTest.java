package com.taolesi.mcthread.experiment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final class FakeLock implements BlockEntityTickLock {
        private final Object lock = new Object();

        @Override
        public Object mcthread$tickLock() {
            return lock;
        }
    }
}
