package com.taolesi.threadtearer.mek;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread Tearer — Mek addon: relocates Mekanism block-entity tick compute onto
 * the Thread Tearer compute pool. Vanilla layout is preserved when the Thread
 * Tearer core is disabled or Mekanism is not installed (the mixin plugin gates
 * every entry point on Mekanism class presence).
 */
@Mod(ThreadTearerMek.MODID)
public final class ThreadTearerMek {

    public static final String MODID = "threadtearer_mek";
    public static final Logger LOGGER = LoggerFactory.getLogger("ThreadTearer.Mek");

    public ThreadTearerMek(IEventBus modBus, ModContainer container) {
        LOGGER.info("[Thread Tearer — Mek] 0.3.4 loaded; Thread Tearer + Mekanism required for relocation to apply");
        if (MekOffloadPolicy.disabled()) {
            LOGGER.warn("[Thread Tearer — Mek] offloading DISABLED by -Dthreadtearer.mek.offload=false; "
                    + "all Mekanism ticks run on the server thread");
        } else {
            LOGGER.info("[Thread Tearer — Mek] offloading the recipe/factory/chemical machines that extend "
                    + "{} and are not on the {}-entry exception list; their ejector push is deferred to the "
                    + "server thread. Every other Mekanism tile keeps its tick.",
                    "TileEntityConfigurableMachine", MekOffloadPolicy.deniedSize());
        }
    }
}
