package com.taolesi.threadtearer.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batches neighbour updates triggered by compute-thread block writes
 * ({@code LevelMixin.setBlock} light-write path) to the server-tick
 * boundary.
 *
 * <p>A vanilla {@code Level.setBlock(pos, state, 3)} notifies all six
 * neighbours immediately; with a Mekanism base those neighbours are often
 * machines whose {@code neighborChanged} re-checks redstone. Doing that
 * per write costs ~1.5ms; batching to once per position per tick collapses
 * the redundant notifications.
 *
 * <p>Player and other server-thread writes are NOT batched — they keep
 * vanilla immediate-update semantics. Only compute-thread writes accept
 * the one-tick neighbour-update delay (machines re-check redstone next
 * tick anyway).
 */
public final class NeighborUpdateBatch {

    private static final ConcurrentHashMap<Level, ConcurrentHashMap<BlockPos, Block>> PENDING =
            new ConcurrentHashMap<>();

    private NeighborUpdateBatch() {
    }

    /** Compute thread: record that {@code pos} changed to a block of {@code block}. */
    public static void record(Level level, BlockPos pos, Block block) {
        PENDING.computeIfAbsent(level, l -> new ConcurrentHashMap<>())
                .put(pos.immutable(), block);
    }

    /** True when nothing is pending (used by the coalescer flush gate). */
    public static boolean isEmpty() {
        return PENDING.isEmpty();
    }

    /**
     * Server thread, tick boundary: run the deduplicated neighbour updates.
     * Uses the public {@code updateNeighborsAt} (same notification vanilla
     * performs per write) plus the comparator update for signal-carrying
     * blocks.
     */
    public static void flush() {
        for (Map.Entry<Level, ConcurrentHashMap<BlockPos, Block>> entry : PENDING.entrySet()) {
            Level level = entry.getKey();
            ConcurrentHashMap<BlockPos, Block> batch = PENDING.remove(level);
            if (batch == null || batch.isEmpty()) {
                continue;
            }
            for (Map.Entry<BlockPos, Block> update : batch.entrySet()) {
                BlockPos pos = update.getKey();
                Block block = update.getValue();
                if (!level.isLoaded(pos)) {
                    continue;
                }
                long t0 = System.nanoTime();
                level.updateNeighborsAt(pos, block);
                if (level.getBlockState(pos).hasAnalogOutputSignal()) {
                    level.updateNeighbourForOutputSignal(pos, block);
                }
                com.taolesi.threadtearer.monitor.PhaseTimings.NEIGHBOR_POSITIONS.increment();
                com.taolesi.threadtearer.monitor.PhaseTimings.NEIGHBOR_NANOS
                        .add(System.nanoTime() - t0);
            }
        }
    }

    /** Clears everything (server stopping). */
    public static void clear() {
        PENDING.clear();
    }
}