package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.ComputeTask;
import com.taolesi.mcthread.experiment.InteractionRelocator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compute pool for block-entity ticks and adapters.
 *
 * <p>Grows to {@code max} workers as soon as tasks arrive in parallel, then
 * queues. Idle workers time out. Live world writes still go to interaction.
 */
public final class ComputePool implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final AtomicLong completedTicks = new AtomicLong();

    public ComputePool(int threadCount) {
        int max = threadCount <= 0
                ? Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
                : threadCount;
        ScalingQueue queue = new ScalingQueue();
        this.executor = new ThreadPoolExecutor(
                1, max, 60L, TimeUnit.SECONDS,
                queue,
                new NamedDaemonThreadFactory("MCT-Compute"),
                enqueueOnReject());
        this.executor.allowCoreThreadTimeOut(true);
        queue.attach(this.executor);
    }

    public <T> CompletableFuture<T> submit(ComputeTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.compute();
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, asComputeExecutor());
    }

    public int poolSize() {
        return executor.getMaximumPoolSize();
    }

    public int liveWorkers() {
        return executor.getPoolSize();
    }

    public int activeWorkers() {
        return executor.getActiveCount();
    }

    public long drainCompletedTicks() {
        return completedTicks.getAndSet(0L);
    }

    /** Raw executor for {@link OptimisticRunner}. */
    public Executor asExecutor() {
        return executor;
    }

    /**
     * Block-entity compute: marks the worker and grows toward every core
     * when many tickers are in flight.
     */
    public Executor asComputeExecutor() {
        return command -> {
            adjustForLoad();
            executor.execute(() -> {
                InteractionRelocator.enterCompute();
                try {
                    command.run();
                    completedTicks.incrementAndGet();
                } finally {
                    InteractionRelocator.leaveCompute();
                }
            });
        };
    }

    void adjustForLoad() {
        int load = executor.getQueue().size() + executor.getActiveCount() + 1;
        int max = executor.getMaximumPoolSize();
        int target = Math.min(max, Math.max(1, load));
        if (target != executor.getCorePoolSize()) {
            executor.setCorePoolSize(target);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static RejectedExecutionHandler enqueueOnReject() {
        return (runnable, pool) -> {
            if (pool.isShutdown()) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            ((ScalingQueue) pool.getQueue()).enqueue(runnable);
        };
    }

    /**
     * Refuse the queue while the pool can still grow so {@code execute}
     * creates another worker. After {@code max}, tasks wait in the queue.
     */
    private static final class ScalingQueue extends LinkedBlockingQueue<Runnable> {

        private volatile ThreadPoolExecutor pool;

        void attach(ThreadPoolExecutor pool) {
            this.pool = pool;
        }

        void enqueue(Runnable runnable) {
            super.offer(runnable);
        }

        @Override
        public boolean offer(Runnable runnable) {
            ThreadPoolExecutor p = this.pool;
            if (p != null && p.getPoolSize() < p.getMaximumPoolSize()) {
                return false;
            }
            return super.offer(runnable);
        }
    }
}
