package com.taolesi.threadtearer.enderio;

import com.taolesi.threadtearer.api.MCT;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The addon's mod entry point. All real work happens in the mixin layer and
 * {@link EnderOffloadPolicy}; this class only logs startup state.
 */
@Mod(ThreadTearerEnderIO.MOD_ID)
public class ThreadTearerEnderIO {

    public static final String MOD_ID = "threadtearer_eio";

    private static final Logger LOG = LoggerFactory.getLogger("Thread Tearer.EIO");

    public ThreadTearerEnderIO(IEventBus modBus, ModContainer container) {
        if (!EnderIOMixinPlugin.ifPresent) {
            LOG.info("[Thread Tearer] enderio absent, addon idle");
            return;
        }
        if (EnderOffloadPolicy.disabled()) {
            LOG.info("[Thread Tearer] EIO offload disabled (-Dthreadtearer.enderio.offload=false)");
            return;
        }
        LOG.info("[Thread Tearer] EIO addon loaded, {} crafting machines offloadable, runtime={}",
                EnderOffloadPolicy.allowedSize(), MCT.runtime().getClass().getSimpleName());
    }
}