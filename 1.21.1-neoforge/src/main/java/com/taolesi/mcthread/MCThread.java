package com.taolesi.mcthread;

import com.mojang.logging.LogUtils;
import com.taolesi.mcthread.adapter.SyntheticCraftAdapter;
import com.taolesi.mcthread.command.MCTCommand;
import com.taolesi.mcthread.config.MCThreadConfig;
import com.taolesi.mcthread.gametest.MCTGameTests;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Thread Tearer for Minecraft 1.21.1 NeoForge.
 *
 * <p>The value in {@link #MODID} must match {@code modId} in META-INF/neoforge.mods.toml.
 */
@Mod(MCThread.MODID)
public final class MCThread {

    public static final String MODID = "mcthread";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MCThread(IEventBus modEventBus, ModContainer container) {
        new MCTRuntimeImpl();
        com.taolesi.mcthread.api.MCT.install(MCTRuntimeImpl.get());

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(MCTGameTests::register);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.addListener(MCTCommand::register);
        NeoForge.EVENT_BUS.register(MCTRuntimeImpl.get().monitor());

        container.registerConfig(ModConfig.Type.COMMON, MCThreadConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Thread Tearer] 1.21.1-neoforge loaded (mod_version={})", "0.1.0");
        LOGGER.info("[Thread Tearer] runtime.enabled={}, computeThreads={}, profiler.enabled={}, experiment.offloadPlayerUseItem={}",
                MCThreadConfig.runtimeEnabled, MCThreadConfig.computeThreads, MCThreadConfig.profilerEnabled,
                MCThreadConfig.offloadPlayerUseItem);

        MCTRuntimeImpl.get().adapterRegistry().register(new SyntheticCraftAdapter());
        MCTRuntimeImpl.get().adapterRegistry().refresh();
        LOGGER.info("[Thread Tearer] adapters: {}",
                MCTRuntimeImpl.get().adapterRegistry().attachedAdapters().stream()
                        .map(adapter -> adapter.domainId()).toList());
        LOGGER.info("[Thread Tearer] commands registered: /mcthread (prof/replay/bench/monitor/adapters/runtime/demo/experiment/selftest)");
        LOGGER.info("[Thread Tearer] mixin config=mcthread.mixins.json (look for Selecting config mcthread.mixins.json in debug.log)");
    }

    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        MCTRuntimeImpl.get().onServerStarting(event.getServer());
        com.taolesi.mcthread.experiment.ForgeBusRelocator.logDetectedBuses();
        LOGGER.info("[Thread Tearer] server starting, runtime attached (compute threads={})",
                MCTRuntimeImpl.get().computePoolSize());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        MCTRuntimeImpl.get().onServerStopping();
        LOGGER.info("[Thread Tearer] server stopping, runtime detached");
    }

    @SubscribeEvent
    public void onServerTick(final ServerTickEvent.Post event) {
        MCTRuntimeImpl.get().onServerTickEnd();
    }
}
