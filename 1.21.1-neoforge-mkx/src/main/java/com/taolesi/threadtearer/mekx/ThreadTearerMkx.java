package com.taolesi.threadtearer.mekx;

import com.taolesi.threadtearer.api.MCT;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The addon's mod entry point. All real work happens in the mixin layer and
 * {@link MkxOffloadPolicy}; this class only logs startup state.
 */
@Mod(ThreadTearerMkx.MOD_ID)
public class ThreadTearerMkx {

    public static final String MOD_ID = "threadtearer_mkx";

    private static final Logger LOG = LoggerFactory.getLogger("Thread Tearer.MKX");

    public ThreadTearerMkx(IEventBus modBus, ModContainer container) {
        if (!MkxMixinPlugin.ifPresent) {
            LOG.info("[Thread Tearer] mekanism absent, mkx addon idle");
            return;
        }
        if (MkxOffloadPolicy.disabled()) {
            LOG.info("[Thread Tearer] MKX offload disabled (-Dthreadtearer.mkx.offload=false)");
            return;
        }
        LOG.info("[Thread Tearer] MKX addon loaded, {} factories offloadable, runtime={}",
                MkxOffloadPolicy.allowedSize(), MCT.runtime().getClass().getSimpleName());
    }
}