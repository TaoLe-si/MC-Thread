package com.taolesi.threadtearer.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputePoolTest {

    @Test
    void computesValue() throws Exception {
        try (ComputePool pool = new ComputePool(2)) {
            assertEquals(42, pool.submit(() -> 40 + 2).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void propagatesFailure() {
        try (ComputePool pool = new ComputePool(2)) {
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> pool.submit(() -> {
                        throw new IllegalStateException("boom");
                    }).join());
            assertInstanceOf(IllegalStateException.class, ex.getCause());
        }
    }

    @Test
    void threadsAreDaemonAndNamed() throws Exception {
        try (ComputePool pool = new ComputePool(1)) {
            AtomicReference<String> name = new AtomicReference<>();
            AtomicReference<Boolean> daemon = new AtomicReference<>();
            pool.submit(() -> {
                name.set(Thread.currentThread().getName());
                daemon.set(Thread.currentThread().isDaemon());
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertTrue(name.get().startsWith("MCT-Compute-"), "unexpected thread name: " + name.get());
            assertTrue(daemon.get(), "compute threads must be daemon");
        }
    }

    @Test
    void scalesWorkersWhenEveryThreadIsBusy() throws Exception {
        try (ComputePool pool = new ComputePool(4)) {
            CountDownLatch started = new CountDownLatch(4);
            CountDownLatch hold = new CountDownLatch(1);
            Set<String> threads = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    threads.add(Thread.currentThread().getName());
                    started.countDown();
                    awaitHold(hold);
                }, pool.asExecutor()));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "expected 4 compute workers under load");
            assertEquals(4, threads.size(), "expected one worker per core-bound task, got " + threads);
            hold.countDown();
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockEntityTicksRunOnSeparateComputeWorkers() throws Exception {
        try (ComputePool pool = new ComputePool(4)) {
            CountDownLatch started = new CountDownLatch(4);
            CountDownLatch hold = new CountDownLatch(1);
            Set<String> threads = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    threads.add(Thread.currentThread().getName());
                    started.countDown();
                    awaitHold(hold);
                }, pool.asComputeExecutor()));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "expected 4 MCT-Compute workers for parallel block-entity ticks");
            assertEquals(4, threads.size(), "expected one compute worker per in-flight ticker, got " + threads);
            for (String name : threads) {
                assertTrue(name.startsWith("MCT-Compute-"), "unexpected tick thread: " + name);
            }
            hold.countDown();
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
    }

    private static void awaitHold(CountDownLatch hold) {
        try {
            if (!hold.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hold was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
