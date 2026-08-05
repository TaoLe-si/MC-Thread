package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.StaleSnapshotException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimisticRunnerTest {

    private ExecutorService server;
    private ExecutorService compute;
    private OptimisticRunner runner;

    @BeforeEach
    void setUp() {
        server = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-server"));
        compute = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-compute"));
        runner = new OptimisticRunner(server, compute);
    }

    @AfterEach
    void tearDown() {
        server.shutdownNow();
        compute.shutdownNow();
    }

    @Test
    void successPath() throws Exception {
        AtomicReference<String> computeThread = new AtomicReference<>();
        AtomicReference<String> commitThread = new AtomicReference<>();
        AtomicLong version = new AtomicLong(1);

        long result = runner.run(
                () -> new SnapshotImpl<>(version.get(), version.get()),
                snapshot -> {
                    computeThread.set(Thread.currentThread().getName());
                    return snapshot.get() + 2;
                },
                (snapshot, value) -> snapshot.isValid(version.get()),
                value -> commitThread.set(Thread.currentThread().getName()),
                2).get(5, TimeUnit.SECONDS);

        assertEquals(3, result);
        assertTrue(computeThread.get().startsWith("test-compute"), "compute must run on compute executor");
        assertTrue(commitThread.get().startsWith("test-server"), "commit must run on server executor");
    }

    @Test
    void staleSnapshotRetriesUntilValid() throws Exception {
        AtomicLong version = new AtomicLong(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failFirst = new AtomicBoolean(true);

        long result = runner.run(
                () -> {
                    attempts.incrementAndGet();
                    return new SnapshotImpl<>(version.get(), version.get());
                },
                snapshot -> snapshot.get() * 2,
                (snapshot, value) -> {
                    if (failFirst.getAndSet(false)) {
                        version.incrementAndGet();
                        return false;
                    }
                    return snapshot.isValid(version.get());
                },
                value -> {
                },
                3).get(5, TimeUnit.SECONDS);

        assertEquals(4, result);
        assertEquals(2, attempts.get(), "exactly one retry after the stale attempt");
    }

    @Test
    void retryBudgetExhaustedThrowsStaleSnapshot() {
        AtomicLong version = new AtomicLong(1);
        AtomicInteger commits = new AtomicInteger();

        CompletionException ex = assertThrows(CompletionException.class,
                () -> runner.run(
                        () -> new SnapshotImpl<>(version.get(), version.get()),
                        snapshot -> snapshot.get(),
                        (snapshot, value) -> {
                            version.incrementAndGet();
                            return false;
                        },
                        value -> commits.incrementAndGet(),
                        2).join());

        assertInstanceOf(StaleSnapshotException.class, ex.getCause());
        assertEquals(0, commits.get(), "commit must never run when validation fails");
    }

    @Test
    void computeFailurePropagates() {
        CompletionException ex = assertThrows(CompletionException.class,
                () -> runner.run(
                        () -> new SnapshotImpl<>(1L, 1L),
                        snapshot -> {
                            throw new IllegalStateException("compute boom");
                        },
                        (snapshot, value) -> true,
                        value -> {
                        },
                        2).join());

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}
