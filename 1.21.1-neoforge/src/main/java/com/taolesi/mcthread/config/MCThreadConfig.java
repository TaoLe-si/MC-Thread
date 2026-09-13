package com.taolesi.mcthread.config;

import com.taolesi.mcthread.MCThread;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration for Thread Tearer.
 *
 * <p>Every optimization will be individually toggleable so that behavior changes can
 * be A/B benchmarked and rolled back independently (see docs/02-development-plan.md).
 */
@EventBusSubscriber(modid = MCThread.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class MCThreadConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue RUNTIME_ENABLED = BUILDER
            .comment("Master switch for the Thread Tearer runtime layer.")
            .define("runtime.enabled", true);

    private static final ModConfigSpec.IntValue COMPUTE_THREADS = BUILDER
            .comment("Number of compute threads for pure-compute tasks. 0 = auto (available cores - 1).")
            .defineInRange("runtime.computeThreads", 0, 0, 64);

    private static final ModConfigSpec.BooleanValue PROFILER_ENABLED = BUILDER
            .comment("Enable per-mod / per-event tick attribution profiling.")
            .define("profiler.enabled", true);

    private static final ModConfigSpec.IntValue PROFILER_INTERVAL_TICKS = BUILDER
            .comment("Profiler aggregation interval in ticks.")
            .defineInRange("profiler.intervalTicks", 100, 20, 1200);

    private static final ModConfigSpec.BooleanValue REPLAY_ENABLED = BUILDER
            .comment("Enable the replay event stream (per-tick metrics session log).")
            .define("replay.enabled", true);

    private static final ModConfigSpec.IntValue REPLAY_MAX_ENTRIES = BUILDER
            .comment("Maximum number of replay entries kept in the in-memory ring buffer.")
            .defineInRange("replay.maxEntries", 100_000, 100, 1_000_000);

    private static final ModConfigSpec.BooleanValue MONITOR_ENABLED = BUILDER
            .comment("Enable the game-change monitor (chunk load/unload, entity join/leave counters).")
            .define("monitor.enabled", true);

    private static final ModConfigSpec.IntValue MONITOR_LOG_INTERVAL_TICKS = BUILDER
            .comment("How often the monitor writes a summary log line, in ticks. 0 disables periodic logging "
                    + "(counters still flow into replay exports).")
            .defineInRange("monitor.logIntervalTicks", 100, 0, 72000);

    private static final ModConfigSpec.BooleanValue OPT_CAPABILITY_CACHE = BUILDER
            .comment("Legacy Forge CapabilityProvider cache. Unused on NeoForge 1.21.1 "
                    + "(capabilities are no longer stored on CapabilityProvider).")
            .define("optimizations.capabilityCache", false);

    private static final ModConfigSpec.BooleanValue OPT_ASYNC_EXPORT = BUILDER
            .comment("Write profile/replay/bench JSON exports asynchronously on the interaction "
                    + "thread instead of blocking the server thread (M3).")
            .define("optimizations.asyncExport", true);

    private static final ModConfigSpec.BooleanValue EXP_OFFLOAD_PLAYER_USE_ITEM = BUILDER
            .comment("Relocate vanilla game-logic methods onto the interaction thread. "
                    + "Other mods' Mixins on those methods run there too. Default ON. "
                    + "Block-entity ticks run on the compute pool.")
            .define("experiments.offloadPlayerUseItem", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean runtimeEnabled;
    public static int computeThreads;
    public static boolean profilerEnabled;
    public static int profilerIntervalTicks;
    public static boolean replayEnabled;
    public static int replayMaxEntries;
    public static boolean monitorEnabled;
    public static int monitorLogIntervalTicks;
    public static boolean capabilityCache;
    public static boolean asyncExport;
    public static boolean offloadPlayerUseItem;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        runtimeEnabled = RUNTIME_ENABLED.get();
        computeThreads = COMPUTE_THREADS.get();
        profilerEnabled = PROFILER_ENABLED.get();
        profilerIntervalTicks = PROFILER_INTERVAL_TICKS.get();
        replayEnabled = REPLAY_ENABLED.get();
        replayMaxEntries = REPLAY_MAX_ENTRIES.get();
        monitorEnabled = MONITOR_ENABLED.get();
        monitorLogIntervalTicks = MONITOR_LOG_INTERVAL_TICKS.get();
        capabilityCache = OPT_CAPABILITY_CACHE.get();
        asyncExport = OPT_ASYNC_EXPORT.get();
        offloadPlayerUseItem = EXP_OFFLOAD_PLAYER_USE_ITEM.get();

        if (com.taolesi.mcthread.runtime.MCTRuntimeImpl.get() != null) {
            com.taolesi.mcthread.runtime.MCTRuntimeImpl.get().onConfigReload();
        }
    }

    private MCThreadConfig() {
    }
}
