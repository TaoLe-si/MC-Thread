package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.ComputeTask;
import com.taolesi.threadtearer.experiment.InteractionRelocator;

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
    /** Per-interval multi-core evidence: CPU time and per-worker task counts. */
    private final AtomicLong cpuNanos = new AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.LongAdder> perWorker =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * Tasks waiting for a worker. Used as backpressure: if this grows without
     * bound the pool is the bottleneck, and each queued task runs against
     * ever-staler world state while new ones keep arriving — a feedback loop
     * that ends in a runaway queue. Callers stop offloading while it is deep
     * and let vanilla run instead.
     */
    public int queueDepth() {
        return executor.getQueue().size();
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
                long t0 = System.nanoTime();
                InteractionRelocator.enterCompute();
                try {
                    command.run();
                    completedTicks.incrementAndGet();
                } finally {
                    InteractionRelocator.leaveCompute();
                    cpuNanos.addAndGet(System.nanoTime() - t0);
                    perWorker.computeIfAbsent(Thread.currentThread().getName(),
                            k -> new java.util.concurrent.atomic.LongAdder()).increment();
                }
            });
        };
    }

    /**
     * Answers "are the cores actually being used": how much CPU time the
     * compute tasks consumed, how many distinct workers ran them, and the
     * busiest worker's share. Divide {@code cpuNanos} by the wall-clock time
     * of the same interval to get the parallelism factor — anything above ~1
     * means real multi-core work.
     */
    public record Stats(long tasks, long cpuNanos, int workers, int busiestWorkerTasks) {
    }

    /** Drains the per-interval compute statistics. */
    public Stats drainStats() {
        long tasks = 0;
        long busiest = 0;
        int workers = 0;
        for (var entry : perWorker.entrySet()) {
            long n = entry.getValue().sumThenReset();
            if (n <= 0) {
                continue;
            }
            workers++;
            tasks += n;
            busiest = Math.max(busiest, n);
        }
        // Drop entries that went idle so the map does not pin dead names.
        perWorker.entrySet().removeIf(e -> e.getValue().sum() == 0);
        return new Stats(tasks, cpuNanos.getAndSet(0), workers, (int) busiest);
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
