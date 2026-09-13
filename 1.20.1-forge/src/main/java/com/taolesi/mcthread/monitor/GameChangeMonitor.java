package com.taolesi.mcthread.monitor;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
        return onTickEnd(tick, 0L, 0, 0, 0);
    }

    public TickDelta onTickEnd(int tick, long computeTicks, int computeActive, int computeLive, int computeMax) {
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
                LOGGER.info("tick={} chunkLoads={} chunkUnloads={} entityJoins={} entityLeaves={} computeTicks={} computeActive={} computeLive={} computeMax={}",
                        delta.tick(), delta.chunkLoads(), delta.chunkUnloads(),
                        delta.entityJoins(), delta.entityLeaves(),
                        computeTicks, computeActive, computeLive, computeMax);
            }
        }
        return delta;
    }

    public TickDelta lastDelta() {
        return lastDelta;
    }
}
