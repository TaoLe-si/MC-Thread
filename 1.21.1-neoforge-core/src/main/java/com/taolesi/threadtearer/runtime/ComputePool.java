package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.ComputeTask;
import com.taolesi.threadtearer.experiment.InteractionRelocator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compute pool for block-entity ticks and adapters.
 *
 * <p>Every task spawns a worker until {@code max} are live, then tasks queue.
 * Idle workers time out. Live world writes still go to interaction.
 *
 * <p><b>The dispatch path is deliberately lock-free.</b> {@code corePoolSize}
 * equals {@code maxPoolSize}, so {@link ThreadPoolExecutor#execute} answers
 * "start a worker or queue it" by reading the packed {@code ctl} field — no
 * lock, no worker-list walk. An earlier version instead kept one core thread
 * and hand-rolled the growth: a queue whose {@code offer} returned false while
 * the pool could still grow, plus an {@code adjustForLoad()} that read
 * {@code getQueue().size()}, {@code getActiveCount()} and
 * {@code setCorePoolSize()} on every submission. Both of those take the pool's
 * {@code mainLock} and walk every worker. Measured with the profiler, that pair
 * was the server thread's top two frames — {@code ScalingQueue.offer} at
 * 6.3 ms/tick and {@code adjustForLoad} at 4.3 ms/tick, ~20% of a 52 ms tick —
 * to hand out tasks that then cost 34 µs each on a worker. Reaching the same
 * worker count through the executor's own counter removed all of it.
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
        // core == max on purpose; see the class javadoc. An unbounded queue
        // would normally make maxPoolSize unreachable (the executor only grows
        // past core when offer fails), but core is already max, so the worker
        // count is decided by workerCountOf(ctl) and the queue only ever holds
        // tasks when every worker is busy.
        this.executor = new ThreadPoolExecutor(
                max, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedDaemonThreadFactory("MCT-Compute"));
        this.executor.allowCoreThreadTimeOut(true);
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

    /**
     * The executor's core size, which must equal {@link #poolSize()}: that
     * equality is what keeps {@code execute} on the lock-free
     * {@code workerCountOf(ctl)} branch instead of the queue, and it is the
     * whole point of the class javadoc. Package-private so a test can pin it.
     */
    int coreSize() {
        return executor.getCorePoolSize();
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
     * Block-entity compute: marks the worker so its world writes are
     * recognised, and collects the per-worker statistics the monitor reads.
     */
    public Executor asComputeExecutor() {
        return command -> executor.execute(() -> {
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

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
