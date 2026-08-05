package com.taolesi.mcthread;

import com.mojang.logging.LogUtils;
import com.taolesi.mcthread.adapter.SyntheticCraftAdapter;
import com.taolesi.mcthread.command.MCTCommand;
import com.taolesi.mcthread.config.MCThreadConfig;
import com.taolesi.mcthread.gametest.MCTGameTests;
import com.taolesi.mcthread.runtime.MCTRuntimeImpl;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * MC Thread - a runtime-layer optimization mod for Minecraft 1.20.1 NeoForge.
 *
 * <p>The value in {@link #MODID} must match {@code modId} in META-INF/mods.toml.
 * The current version only contains the framework skeleton (lifecycle + config).
 * Runtime modules (profiler, task runtime, snapshot/validation primitives,
 * generic hot-path optimizations) are added incrementally per docs/02-development-plan.md.
 */
@Mod(MCThread.MODID)
public final class MCThread {

    public static final String MODID = "mcthread";
    private static final Logger LOGGER = LogUtils.getLogger();

    public MCThread() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Install the runtime facade before anything else can use it.
        new MCTRuntimeImpl();
        com.taolesi.mcthread.api.MCT.install(MCTRuntimeImpl.get());

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(MCTCommand::register);
        MinecraftForge.EVENT_BUS.addListener(MCTGameTests::register);
        MinecraftForge.EVENT_BUS.register(MCTRuntimeImpl.get().monitor());

        // Common config: runtime / profiler / optimization toggles.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MCThreadConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[MC Thread] framework skeleton loaded (mod_version={})", "0.1.0");
        LOGGER.info("[MC Thread] runtime.enabled={}, computeThreads={}, profiler.enabled={}",
                MCThreadConfig.runtimeEnabled, MCThreadConfig.computeThreads, MCThreadConfig.profilerEnabled);

        // Register reference adapter (always available) and refresh the registry.
        MCTRuntimeImpl.get().adapterRegistry().register(new SyntheticCraftAdapter());
        MCTRuntimeImpl.get().adapterRegistry().refresh();
        LOGGER.info("[MC Thread] adapters: {}",
                MCTRuntimeImpl.get().adapterRegistry().attachedAdapters().stream()
                        .map(adapter -> adapter.domainId()).toList());
        LOGGER.info("[MC Thread] commands registered: /mcthread (prof/replay/bench/monitor/adapters/runtime/demo/selftest)");
    }

    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        MCTRuntimeImpl.get().onServerStarting(event.getServer());
        LOGGER.info("[MC Thread] server starting, runtime attached (compute threads={})",
                MCTRuntimeImpl.get().computePoolSize());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event) {
        MCTRuntimeImpl.get().onServerStopping();
        LOGGER.info("[MC Thread] server stopping, runtime detached");
    }

    @SubscribeEvent
    public void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MCTRuntimeImpl.get().onServerTickEnd();
        }
    }
}
