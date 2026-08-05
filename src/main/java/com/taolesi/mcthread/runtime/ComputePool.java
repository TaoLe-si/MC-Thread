package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.ComputeTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-size pool for pure-compute tasks.
 *
 * <p>Workers must never touch live world state; the contract is enforced by
 * API documentation and tests, not by runtime interception (Rule-011).
 */
public final class ComputePool implements AutoCloseable {

    private final ThreadPoolExecutor executor;

    public ComputePool(int threadCount) {
        int n = threadCount <= 0
                ? Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
                : threadCount;
        this.executor = new ThreadPoolExecutor(
                n, n, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedDaemonThreadFactory("MCT-Compute"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public <T> CompletableFuture<T> submit(ComputeTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.compute();
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, executor);
    }

    public int poolSize() {
        return executor.getMaximumPoolSize();
    }

    /** Exposes the underlying executor for {@link OptimisticRunner}. */
    public Executor asExecutor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
