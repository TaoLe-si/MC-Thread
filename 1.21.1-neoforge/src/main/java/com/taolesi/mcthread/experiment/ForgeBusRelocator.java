package com.taolesi.mcthread.experiment;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers every NeoForge {@code IEventBus} instance. Gameplay {@code post} is
 * <em>not</em> stolen: result-collecting events must finish before the caller continues.
 */
public final class ForgeBusRelocator {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Interaction");

    private ForgeBusRelocator() {
    }

    public static void logDetectedBuses() {
        Map<IEventBus, List<String>> owners = new IdentityHashMap<>();
        add(owners, NeoForge.EVENT_BUS, "NeoForge.EVENT_BUS");
        ModList.get().forEachModContainer((modId, container) -> {
            if (container instanceof FMLModContainer fml) {
                IEventBus bus = fml.getEventBus();
                if (bus != null) {
                    add(owners, bus, "mod:" + modId);
                }
            }
        });
        LOGGER.info("[Thread Tearer] detected {} NeoForge event bus instance(s) (post stays synchronous)", owners.size());
        owners.forEach((bus, names) -> LOGGER.info("[Thread Tearer] bus {} class={} owners={}",
                System.identityHashCode(bus), bus.getClass().getName(), names));
    }

    private static void add(Map<IEventBus, List<String>> owners, IEventBus bus, String name) {
        owners.computeIfAbsent(bus, key -> new ArrayList<>()).add(name);
    }
}
