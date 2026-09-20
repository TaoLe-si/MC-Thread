package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.monitor.PhaseTimings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-position world bookkeeping requested by compute-thread writes and
 * block-entity ticks, collapsed to one entry per position per tick and flushed
 * at the tick boundary on the server thread.
 *
 * <p>Three flags ride along, because all three are idempotent, keyed purely by
 * position, and trivially repeatable — a single vanilla {@code setChanged()}
 * emits two of them:
 *
 * <ul>
 *   <li>{@code notifyNeighbours} — {@code Level.updateNeighborsAt}. A vanilla
 *       {@code setBlock(pos, state, 3)} notifies all six neighbours
 *       immediately; with a machine base those neighbours are often machines
 *       whose {@code neighborChanged} re-checks redstone. Doing that per write
 *       costs ~1.5ms; once per position per tick collapses the redundant
 *       notifications.</li>
 *   <li>{@code signal} — {@code Level.updateNeighbourForOutputSignal}, the
 *       comparator fan-out. Callers set it only when vanilla would: always from
 *       {@code setChanged} / {@code removeBlockEntity}, and from the
 *       {@code setBlock} light path only when the new state carries an analog
 *       output signal. Re-read at flush time, so one call per tick sees the
 *       tick's final state.</li>
 *   <li>{@code unsaved} — {@code Level.blockEntityChanged}, whose whole body is
 *       {@code chunk.setUnsaved(true)}. This is the one that made batching
 *       necessary rather than merely nice: Titanium's progress bar calls
 *       {@code setProgress} — and therefore {@code markComponentForUpdate} →
 *       {@code setChanged} → this method — unconditionally from
 *       {@code ActiveTile.serverTick} every tick, and the inventory / tank /
 *       energy components do the same on every slot change. Each call used to
 *       become its own deferred apply task (thousands per tick on a loaded
 *       base); it is a boolean flag.</li>
 * </ul>
 *
 * <p>The {@code Block} argument those two notifications take is re-read from
 * the live world at flush time rather than carried from the caller: the worker
 * computed its value from a read that may be a tick stale, and the latest state
 * is exactly what vanilla would pass at that instant. Whether the comparator
 * notification is owed at all is still the caller's decision, because vanilla
 * differs there — {@code setChanged} calls it for any non-air state, while the
 * {@code setBlock} path calls it only for a state carrying an analog signal.
 *
 * <p>Player and other server-thread writes are NOT batched — they keep vanilla
 * immediate-update semantics. Only compute-thread writes accept the one-tick
 * delay (machines re-check redstone next tick anyway).
 *
 * <p>Ordering: entries are applied in unspecified map order, and the flags per
 * position run unsaved → neighbours → signal. Nothing here depends on
 * cross-position order, and vanilla itself has no defined order across
 * unrelated positions.
 */
public final class PositionUpdateBatch {

    /** Mutable per-position accumulator; merged under the position's map key. */
    private static final class Pending {
        boolean notifyNeighbours;
        boolean signal;
        boolean unsaved;
    }

    private static final ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, Pending>> PENDING =
            new ConcurrentHashMap<>();

    private PositionUpdateBatch() {
    }

    /**
     * Compute thread: fold one bookkeeping call for {@code pos} into the tick's
     * batch. Flags merge with OR — two callers wanting the same notification is
     * one notification.
     */
    public static void record(Level level, BlockPos pos,
                              boolean notifyNeighbours, boolean signal, boolean unsaved) {
        PENDING.computeIfAbsent(level, l -> new ConcurrentHashMap<>())
                .compute(pos.immutable(), (key, existing) -> {
                    Pending pending = existing == null ? new Pending() : existing;
                    pending.notifyNeighbours |= notifyNeighbours;
                    pending.signal |= signal;
                    pending.unsaved |= unsaved;
                    return pending;
                });
        PhaseTimings.POSITION_UPDATES.increment();
    }

    /** True when nothing is pending (used by the coalescer flush gate). */
    public static boolean isEmpty() {
        return PENDING.isEmpty();
    }

    /**
     * Server thread, tick boundary: run the deduplicated bookkeeping.
     *
     * <p>Every call here re-checks {@code isLoaded} and re-reads the live block
     * state, so a position that unloaded between the worker's decision and this
     * flush is skipped rather than blocking the server thread on a chunk load.
     *
     * <p>These run with no apply depth on the server thread, so the mixin guards
     * on the methods they call take the inline path — nothing here re-enters the
     * coalescer.
     */
    public static void flush() {
        for (Map.Entry<Level, ConcurrentHashMap<BlockPos, Pending>> entry : PENDING.entrySet()) {
            Level level = entry.getKey();
            ConcurrentHashMap<BlockPos, Pending> batch = PENDING.remove(level);
            if (batch == null || batch.isEmpty()) {
                continue;
            }
            for (Map.Entry<BlockPos, Pending> update : batch.entrySet()) {
                BlockPos pos = update.getKey();
                Pending pending = update.getValue();
                if (!level.isLoaded(pos)) {
                    continue;
                }
                long t0 = System.nanoTime();
                if (pending.unsaved) {
                    level.blockEntityChanged(pos);
                }
                if (pending.notifyNeighbours) {
                    level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
                }
                if (pending.signal) {
                    level.updateNeighbourForOutputSignal(pos, level.getBlockState(pos).getBlock());
                }
                PhaseTimings.NEIGHBOR_POSITIONS.increment();
                PhaseTimings.NEIGHBOR_NANOS.add(System.nanoTime() - t0);
            }
        }
    }

    /** Clears everything (server stopping). */
    public static void clear() {
        PENDING.clear();
    }
}
