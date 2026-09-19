package com.taolesi.threadtearer.monitor;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;

/**
 * Game-change monitor: counts chunk load/unload and entity join/leave events
 * per tick so tick spikes can be correlated with actual world changes.
 *
 * <p>Every tick the per-tick delta is exposed via {@link #lastDelta()} and fed
 * into the replay stream; every {@code logIntervalTicks} a summary line is
 * written to the {@code MCThread.Monitor} logger.
 */
public final class GameChangeMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Monitor");

    public record TickDelta(int tick, int chunkLoads, int chunkUnloads,
                            int entityJoins, int entityLeaves) {
        public static final TickDelta EMPTY = new TickDelta(0, 0, 0, 0, 0);
    }

    private final LongAdder chunkLoads = new LongAdder();
    private final LongAdder chunkUnloads = new LongAdder();
    private final LongAdder entityJoins = new LongAdder();
    private final LongAdder entityLeaves = new LongAdder();

    private volatile boolean enabled = true;
    private volatile int logIntervalTicks = 100;
    private int sinceLastLog;
    private volatile TickDelta lastDelta = TickDelta.EMPTY;

    /**
     * Supplies the compute pool's per-interval statistics. Set by the runtime;
     * only consulted when a log line is actually emitted, so the hot path pays
     * nothing.
     */
    private volatile java.util.function.Supplier<com.taolesi.threadtearer.runtime.ComputePool.Stats>
            computeStats = () -> null;
    private long lastLogNanos;

    public void setComputeStatsSupplier(
            java.util.function.Supplier<com.taolesi.threadtearer.runtime.ComputePool.Stats> supplier) {
        this.computeStats = supplier;
    }

    public void configure(boolean enabled, int logIntervalTicks) {
        this.enabled = enabled;
        this.logIntervalTicks = Math.max(0, logIntervalTicks);
    }

    @SubscribeEvent
    public void onChunkLoad(final ChunkEvent.Load event) {
        if (enabled) {
            chunkLoads.increment();
        }
    }

    @SubscribeEvent
    public void onChunkUnload(final ChunkEvent.Unload event) {
        if (enabled) {
            chunkUnloads.increment();
        }
    }

    @SubscribeEvent
    public void onEntityJoin(final EntityJoinLevelEvent event) {
        if (enabled) {
            entityJoins.increment();
        }
    }

    @SubscribeEvent
    public void onEntityLeave(final EntityLeaveLevelEvent event) {
        if (enabled) {
            entityLeaves.increment();
        }
    }

    /** Call once per server tick; resets the counters and returns this tick's delta. */
    public TickDelta onTickEnd(int tick) {
        return onTickEnd(tick, 0L, 0, 0, 0, 0L, 0L);
    }

    public TickDelta onTickEnd(int tick, long computeTicks, int computeActive, int computeLive, int computeMax) {
        return onTickEnd(tick, computeTicks, computeActive, computeLive, computeMax, 0L, 0L);
    }

    /**
     * Full variant: includes the {@code Level.setBlock} short-circuit counters
     * so the user can see how many redundant writes the no-op skip caught.
     */
    public TickDelta onTickEnd(int tick, long computeTicks, int computeActive, int computeLive, int computeMax,
                              long setBlocksReal, long setBlocksNoOp) {
        TickDelta delta = new TickDelta(tick,
                (int) chunkLoads.sumThenReset(),
                (int) chunkUnloads.sumThenReset(),
                (int) entityJoins.sumThenReset(),
                (int) entityLeaves.sumThenReset());
        lastDelta = delta;
        if (logIntervalTicks > 0) {
            sinceLastLog++;
            if (sinceLastLog >= logIntervalTicks) {
                sinceLastLog = 0;
                long now = System.nanoTime();
                double wallMs = lastLogNanos == 0 ? 0 : (now - lastLogNanos) / 1_000_000.0;
                lastLogNanos = now;
                // WARN so the log_deduplicator mod (which allows only ~10
                // duplicates for WARN vs ~20 for INFO, with fuzzy number
                // matching) still lets a useful sample through; the phase
                // readout in chat (/threadtearer monitor status) is the
                // primary path anyway.
                LOGGER.warn("tick={} computeTicks={} computeActive={} setBlocks={}/{} (real/no-op) {} {}",
                        delta.tick(), computeTicks, computeActive,
                        setBlocksReal, setBlocksNoOp,
                        formatParallelism(wallMs),
                        PhaseTimings.peekAndFormat());
            }
        }
        return delta;
    }

    /**
     * The multi-core verdict: compute CPU time divided by the wall-clock time
     * of the same interval. {@code ~1} means the compute pool finished in
     * about as much CPU time as it consumed (single core); {@code N} means N
     * cores were busy at once. Also reports how many workers actually ran
     * tasks and the busiest one's share, so a lopsided pool is visible.
     */
    private String formatParallelism(double wallMs) {
        com.taolesi.threadtearer.runtime.ComputePool.Stats stats;
        try {
            stats = computeStats.get();
        } catch (Throwable t) {
            return "compute[n/a]";
        }
        if (stats == null || stats.tasks() == 0) {
            return "compute[idle]";
        }
        double cpuMs = stats.cpuNanos() / 1_000_000.0;
        double parallel = wallMs > 0 ? cpuMs / wallMs : 0;
        double perTaskMicros = stats.cpuNanos() / 1000.0 / stats.tasks();
        double busiestPct = 100.0 * stats.busiestWorkerTasks() / stats.tasks();
        // perTask matters as much as parallel: 700 tasks of 7 µs each is
        // 0.05 ms of work per tick — real parallelism but nothing to parallelise.
        return String.format(
                "compute[cpu=%.1fms tasks=%d %.1fµs/task workers=%d parallel=%.3fx busiest=%.0f%%]",
                cpuMs, stats.tasks(), perTaskMicros, stats.workers(), parallel, busiestPct);
    }

    public TickDelta lastDelta() {
        return lastDelta;
    }
}
