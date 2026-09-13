package com.taolesi.mcthread.experiment;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers every Forge {@code IEventBus} instance. Gameplay {@code post} is
 * <em>not</em> stolen: {@code AttachCapabilitiesEvent} and other result-collecting
 * events must finish before the caller continues. Listeners still run on the
 * interaction/tick thread when the vanilla method that posted them was stolen
 * (DEPTH&gt;0).
 */
public final class ForgeBusRelocator {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Interaction");

    private ForgeBusRelocator() {
    }

    /** Logs every IEventBus Forge actually constructed (game bus + each Java mod bus). */
    public static void logDetectedBuses() {
        Map<IEventBus, List<String>> owners = new IdentityHashMap<>();
        add(owners, MinecraftForge.EVENT_BUS, "MinecraftForge.EVENT_BUS");
        ModList.get().forEachModContainer((modId, container) -> {
            if (container instanceof FMLModContainer fml) {
                IEventBus bus = fml.getEventBus();
                if (bus != null) {
                    add(owners, bus, "mod:" + modId);
                }
            }
        });
        LOGGER.info("[MC Thread] detected {} Forge event bus instance(s) (post stays synchronous)", owners.size());
        owners.forEach((bus, names) -> LOGGER.info("[MC Thread] bus {} class={} owners={}",
                System.identityHashCode(bus), bus.getClass().getName(), names));
    }

    private static void add(Map<IEventBus, List<String>> owners, IEventBus bus, String name) {
        owners.computeIfAbsent(bus, key -> new ArrayList<>()).add(name);
    }
}
