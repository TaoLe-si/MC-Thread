package com.taolesi.threadtearer.ie;

import com.taolesi.threadtearer.api.MCT;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The addon's mod entry point. All real work happens in the mixin layer and
 * {@link IeOffloadPolicy}; this class only logs startup state.
 */
@Mod(ThreadTearerIe.MOD_ID)
public class ThreadTearerIe {

    public static final String MOD_ID = "threadtearer_ie";

    private static final Logger LOG = LoggerFactory.getLogger("Thread Tearer.IE");

    public ThreadTearerIe(IEventBus modBus, ModContainer container) {
        if (!IeMixinPlugin.ifPresent) {
            LOG.info("[Thread Tearer] immersiveengineering absent, ie addon idle");
            return;
        }
        if (IeOffloadPolicy.disabled()) {
            LOG.info("[Thread Tearer] IE offload disabled (-Dthreadtearer.ie.offload=false)");
            return;
        }
        LOG.info("[Thread Tearer] IE addon loaded, {} machines offloadable, runtime={}",
                IeOffloadPolicy.allowedSize(), MCT.runtime().getClass().getSimpleName());
    }
}