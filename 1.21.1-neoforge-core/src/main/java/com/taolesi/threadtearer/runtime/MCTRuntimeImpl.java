package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.ComputeTask;
import com.taolesi.threadtearer.api.DomainAdapter;
import com.taolesi.threadtearer.api.InteractionTask;
import com.taolesi.threadtearer.api.MCTRuntime;
import com.taolesi.threadtearer.api.OffloadOutcome;
import com.taolesi.threadtearer.api.Snapshot;
import com.taolesi.threadtearer.api.Transaction;
import com.taolesi.threadtearer.config.MCThreadConfig;
import com.taolesi.threadtearer.experiment.InteractionOffloadPipeline;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.monitor.GameChangeMonitor;
import com.taolesi.threadtearer.profiler.BenchmarkRunner;
import com.taolesi.threadtearer.profiler.ReplayLogger;
import com.taolesi.threadtearer.profiler.TickProfiler;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Concrete {@link MCTRuntime} wiring Minecraft lifecycle events to the runtime components. */
public final class MCTRuntimeImpl implements MCTRuntime {

    private static volatile MCTRuntimeImpl instance;

    private final ComputePool computePool;
    private final InteractionExecutor interactionExecutor;
    private final TickProfiler profiler = new TickProfiler();
    private final ReplayLogger replay = new ReplayLogger();
    private final BenchmarkRunner benchmark = new BenchmarkRunner(profiler, replay);
    private final GameChangeMonitor monitor = new GameChangeMonitor();
    private final OptimisticRunner optimisticRunner;
    private final AdapterRegistry adapterRegistry;
    private final InteractionOffloadPipeline interactionOffload;

    /**
     * Lifecycle thread. NeoForge fires {@code ServerStartingEvent},
     * {@code ServerStoppingEvent} and {@code ServerTickEvent.Post} synchronously
     * on the server thread, which would have the runtime's housekeeping
     * (profiler, replay, monitor) competing for time on the same thread that
     * ticks the world. Routing the lifecycle callbacks through a dedicated
     * thread keeps the server thread free for actual gameplay.
     */
    private final java.util.concurrent.ExecutorService lifecycleExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new NamedDaemonThreadFactory("MCT-Lifecycle"));

    private volatile MinecraftServer server;
    private volatile Thread serverThread;
    private final WriteCoalescer writeCoalescer = new WriteCoalescer();
    private final TickWatchdog watchdog = new TickWatchdog();

    public MCTRuntimeImpl() {
        this.computePool = new ComputePool(MCThreadConfig.computeThreads);
        this.interactionExecutor = new InteractionExecutor();
        this.optimisticRunner = new OptimisticRunner(this::runOnServerThread, computePool.asExecutor());
        this.adapterRegistry = new AdapterRegistry(this);
        this.interactionOffload = new InteractionOffloadPipeline(
                this::runOnServerThread, interactionExecutor.asExecutor(), computePool.asComputeExecutor());
        replay.configure(MCThreadConfig.replayEnabled, MCThreadConfig.replayMaxEntries);
        monitor.configure(MCThreadConfig.monitorEnabled, MCThreadConfig.monitorLogIntervalTicks);
        monitor.setComputeStatsSupplier(computePool::drainStats);
        instance = this;
    }

    /**
     * Public API for addons: hand a world write (called from a compute or
     * tick worker, or from another mod's {@code ServerTickEvent.Post} that
     * triggered a mixin on the off-thread path) to the {@link WriteCoalescer}.
     * The next tick boundary flushes the entire batch as ONE server task,
     * so a mod that itself schedules server work (AE2's TickHandler, etc.)
     * never sees a self-scheduled apply in its own queue.
     */
    public void queueComputeWorldWrite(Runnable command) {
        writeCoalescer.enqueue(command);
    }

    /**
     * Runs {@code command} on the server thread. Inline if the caller is
     * already on the server thread, otherwise queued onto the write
     * coalescer — many calls from compute threads during one game tick
     * (e.g. a Mekanism base running 100 BE ticks per game tick each
     * triggering several setBlock calls) become a single server task at
     * tick boundary, instead of 100+ queued tasks.
     *
     * <p>Server-thread callers use this to land work in the coalescer
     * buffer too. From a {@code ServerTickEvent.Post} handler that uses
     * the interaction FIFO indirectly, this is what prevents the deadlock
     * where an addon runnable schedules its own apply onto the same queue
     * it is currently draining (and waits for it to drain before returning).
     */
    public void runOnServerThread(Runnable command) {
        MinecraftServer s = server;
        if (s == null) {
            throw new IllegalStateException("Thread Tearer runtime has no active server");
        }
        if (isServerThread()) {
            command.run();
        } else {
            writeCoalescer.enqueue(command);
        }
    }

    /**
     * Batches writes from off-thread callers (compute / tick workers) into
     * a single server-thread task per tick. The semantics still match the
     * design contract: the compute thread actively requests the server
     * thread to apply the change — the server thread does not wait for
     * compute to finish. The batching just collapses N requests from one
     * tick into one drain.
     */
    private final class WriteCoalescer {
        private final List<Runnable> buffer = new ArrayList<>();
        private final Object lock = new Object();

        void enqueue(Runnable command) {
            synchronized (lock) {
                buffer.add(command);
            }
        }

        void flush() {
            flushImpl();
        }

        private void flushImpl() {
            List<Runnable> batch;
            synchronized (lock) {
                if (buffer.isEmpty()) {
                    batch = List.of();
                } else {
                    batch = new ArrayList<>(buffer);
                    buffer.clear();
                }
            }
            if (batch.isEmpty() && NeighborUpdateBatch.isEmpty()) {
                return;
            }
            MinecraftServer s = server;
            if (s != null) {
                com.taolesi.threadtearer.monitor.PhaseTimings.FLUSH_CALLS.increment();
                s.execute(() -> {
                    // This lambda runs ON THE SERVER THREAD: its wall time is
                    // what the player feels as latency. Timed as FLUSH_NANOS.
                    long t0 = System.nanoTime();
                    com.taolesi.threadtearer.monitor.PhaseTimings.FLUSH_WRITES.add(batch.size());
                    for (Runnable r : batch) {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            org.slf4j.LoggerFactory.getLogger("ThreadTearer.WriteCoalescer")
                                    .error("Coalesced write failed; continuing with remaining writes", t);
                        }
                    }
                    // Neighbour updates for this tick's light writes run
                    // after the writes themselves, deduped, on this same
                    // server task so ordering is preserved.
                    try {
                        NeighborUpdateBatch.flush();
                    } catch (Throwable t) {
                        org.slf4j.LoggerFactory.getLogger("ThreadTearer.WriteCoalescer")
                                .error("Neighbour update batch failed", t);
                    }
                    com.taolesi.threadtearer.monitor.PhaseTimings.FLUSH_NANOS
                            .add(System.nanoTime() - t0);
                });
            }
        }
    }

    public static MCTRuntimeImpl get() {
        return instance;
    }

    // ---- lifecycle hooks (called from MCThread event listeners) ----
    //
    // These methods are invoked synchronously on the server thread by NeoForge's
    // event bus. The two-line work (set the server / serverThread volatile and
    // bail) is done inline so that {@link #isServerThread()} returns the right
    // answer immediately for any off-thread caller that wakes before the
    // housekeeping lambda runs. Everything else (profiler attach, replay
    // attach, monitor tick, replay record, log) is shipped to the lifecycle
    // executor so the server thread is free within microseconds.

    public void onServerStarting(MinecraftServer s) {
        this.server = s;
        this.serverThread = Thread.currentThread();
        // Publish to the relocator's hot-path cache (one volatile read there
        // instead of three).
        InteractionRelocator.cacheServerThread(this.serverThread);
        watchdog.start(Thread.currentThread());
        lifecycleExecutor.execute(() -> {
            profiler.attach(s);
            replay.attach(s);
            adapterRegistry.refresh();
        });
    }

    public void onServerStopping() {
        MinecraftServer s = this.server;
        Thread t = this.serverThread;
        this.server = null;
        this.serverThread = null;
        InteractionRelocator.cacheServerThread(null);
        watchdog.close();
        lifecycleExecutor.execute(() -> {
            profiler.detach();
            replay.detach();
        });
    }

    public void onServerTickEnd() {
        if (server == null) {
            return;
        }
        watchdog.onTickEnd();
        lifecycleExecutor.execute(() -> {
            // ThreadPoolExecutor.runWorker swallows any Throwable a task
            // throws (afterExecute is a no-op by default), so a failure here
            // used to kill housekeeping — and the write flush with it —
            // completely silently. Isolate each phase and log rate-limited.
            try {
                writeCoalescer.flush();
            } catch (Throwable t) {
                reportLifecycleFailure("flush", t);
            }
            try {
                runTickHousekeeping();
            } catch (Throwable t) {
                reportLifecycleFailure("housekeeping", t);
            }
        });
    }

    private final java.util.concurrent.atomic.AtomicLong lifecycleFailures =
            new java.util.concurrent.atomic.AtomicLong();

    private void reportLifecycleFailure(String phase, Throwable t) {
        long n = lifecycleFailures.incrementAndGet();
        if (n == 1L || n % 100L == 0L) {
            org.slf4j.LoggerFactory.getLogger("MCThread.Lifecycle")
                    .error("[Thread Tearer] lifecycle {} failed ({} times so far)", phase, n, t);
        }
    }

    private void runTickHousekeeping() {
        MinecraftServer s = server;
        if (s == null) {
            return;
        }
        profiler.onTickEnd();
        benchmark.onTick();
        monitor.onTickEnd(s.getTickCount(),
                computePool.drainCompletedTicks(),
                computePool.activeWorkers(),
                computePool.liveWorkers(),
                computePool.poolSize(),
                com.taolesi.threadtearer.monitor.SetBlockCounters.real(),
                com.taolesi.threadtearer.monitor.SetBlockCounters.noOp());
        recordReplay();
    }

    private void recordReplay() {
        MinecraftServer s = server;
        if (s == null || !replay.isSessionOpen()) {
            return;
        }
        double ms = profiler.lastTickMs();
        double tps = Math.min(20.0, 1000.0 / Math.max(1.0, ms));
        int players = s.getPlayerList().getPlayers().size();
        GameChangeMonitor.TickDelta delta = monitor.lastDelta();
        replay.recordTick(s.getTickCount(), tps, ms, players,
                delta.chunkLoads(), delta.chunkUnloads(),
                delta.entityJoins(), delta.entityLeaves(),
                ms > 50.0);
    }

    /** Applies config changes (monitor interval, replay limits) at runtime. */
    public void onConfigReload() {
        replay.configure(MCThreadConfig.replayEnabled, MCThreadConfig.replayMaxEntries);
        monitor.configure(MCThreadConfig.monitorEnabled, MCThreadConfig.monitorLogIntervalTicks);
    }

    // ---- MCTRuntime API ----

    @Override
    public <T> CompletableFuture<T> submitCompute(ComputeTask<T> task) {
        return computePool.submit(task);
    }

    @Override
    public CompletableFuture<Void> scheduleInteraction(InteractionTask task) {
        return interactionExecutor.schedule(task);
    }

    @Override
    public CompletableFuture<OffloadOutcome> scheduleWorldInteraction(String name, Runnable apply) {
        if (isServerThread()) {
            // Vanilla semantics: the caller is already on the owner thread, so
            // run the write inline (inside an apply context) instead of making
            // it queue behind the interaction FIFO.
            try {
                InteractionRelocator.runApply(name, apply);
                return CompletableFuture.completedFuture(OffloadOutcome.APPLIED);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        return interactionOffload.scheduleWorldApply(name, apply);
    }

    @Override
    public void deferWorldWrite(Runnable apply) {
        if (apply == null) {
            return;
        }
        if (isServerThread()) {
            // No compute worker is involved, so the "deferred" write is just a
            // write. Run it now rather than holding it for a tick boundary.
            apply.run();
            return;
        }
        com.taolesi.threadtearer.monitor.PhaseTimings.DEFER_CALLS.increment();
        writeCoalescer.enqueue(apply);
    }

    @Override
    public <T> Snapshot<T> snapshot(T value, long version) {
        return new SnapshotImpl<>(value, version);
    }

    @Override
    public <T> Transaction<T> beginTransaction() {
        return new TransactionImpl<>(this::isServerThread);
    }

    @Override
    public <T, R> CompletableFuture<R> optimistic(
            Supplier<Snapshot<T>> snapshotFactory,
            Function<Snapshot<T>, R> compute,
            BiPredicate<Snapshot<T>, R> validator,
            Consumer<R> commit,
            int maxRetries) {
        return optimisticRunner.run(snapshotFactory, compute, validator, commit, maxRetries);
    }

    @Override
    public boolean isServerThread() {
        Thread t = serverThread;
        return t != null && Thread.currentThread() == t;
    }

    // ---- accessors for commands ----

    public TickProfiler profiler() {
        return profiler;
    }

    public ReplayLogger replay() {
        return replay;
    }

    public BenchmarkRunner benchmark() {
        return benchmark;
    }

    public AdapterRegistry adapterRegistry() {
        return adapterRegistry;
    }

    public GameChangeMonitor monitor() {
        return monitor;
    }

    public InteractionOffloadPipeline interactionOffload() {
        return interactionOffload;
    }

    public MinecraftServer server() {
        return server;
    }

    public int computePoolSize() {
        return computePool.poolSize();
    }

    public int computeLiveWorkers() {
        return computePool.liveWorkers();
    }

    public int computeActiveWorkers() {
        return computePool.activeWorkers();
    }

    public int computeQueueDepth() {
        return computePool.queueDepth();
    }

    public void close() {
        lifecycleExecutor.shutdownNow();
        computePool.close();
        interactionExecutor.close();
    }
}
