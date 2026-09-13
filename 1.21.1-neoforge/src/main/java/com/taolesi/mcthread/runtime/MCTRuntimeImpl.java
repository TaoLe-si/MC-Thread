package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.ComputeTask;
import com.taolesi.mcthread.api.DomainAdapter;
import com.taolesi.mcthread.api.InteractionTask;
import com.taolesi.mcthread.api.MCTRuntime;
import com.taolesi.mcthread.api.Snapshot;
import com.taolesi.mcthread.api.Transaction;
import com.taolesi.mcthread.config.MCThreadConfig;
import com.taolesi.mcthread.experiment.InteractionOffloadPipeline;
import com.taolesi.mcthread.monitor.GameChangeMonitor;
import com.taolesi.mcthread.profiler.BenchmarkRunner;
import com.taolesi.mcthread.profiler.ReplayLogger;
import com.taolesi.mcthread.profiler.TickProfiler;
import net.minecraft.server.MinecraftServer;

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

    private volatile MinecraftServer server;
    private volatile Thread serverThread;

    public MCTRuntimeImpl() {
        this.computePool = new ComputePool(MCThreadConfig.computeThreads);
        this.interactionExecutor = new InteractionExecutor();
        this.optimisticRunner = new OptimisticRunner(this::runOnServerThread, computePool.asExecutor());
        this.adapterRegistry = new AdapterRegistry(this);
        this.interactionOffload = new InteractionOffloadPipeline(
                this::runOnServerThread, interactionExecutor.asExecutor(), computePool.asComputeExecutor());
        replay.configure(MCThreadConfig.replayEnabled, MCThreadConfig.replayMaxEntries);
        monitor.configure(MCThreadConfig.monitorEnabled, MCThreadConfig.monitorLogIntervalTicks);
        instance = this;
    }

    private void runOnServerThread(Runnable command) {
        MinecraftServer s = server;
        if (s == null) {
            throw new IllegalStateException("Thread Tearer runtime has no active server");
        }
        if (isServerThread()) {
            command.run();
        } else {
            s.execute(command);
        }
    }

    public static MCTRuntimeImpl get() {
        return instance;
    }

    // ---- lifecycle hooks (called from MCThread event listeners) ----

    public void onServerStarting(MinecraftServer s) {
        this.server = s;
        this.serverThread = Thread.currentThread();
        profiler.attach(s);
        replay.attach(s);
        adapterRegistry.refresh();
    }

    public void onServerStopping() {
        profiler.detach();
        replay.detach();
        this.server = null;
        this.serverThread = null;
    }

    public void onServerTickEnd() {
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
                computePool.poolSize());
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
}
