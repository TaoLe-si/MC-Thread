package com.taolesi.threadtearer.industrial;

import com.taolesi.threadtearer.api.MCT;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The addon's mod entry point. All real work happens in the mixin layer and
 * the {@link IndustrialOffloadPolicy}; this class only logs startup state.
 */
@Mod(ThreadTearerIndustrial.MOD_ID)
public class ThreadTearerIndustrial {

    public static final String MOD_ID = "threadtearer_if";

    private static final Logger LOG = LoggerFactory.getLogger("ThreadTearer.IF");

    public ThreadTearerIndustrial(IEventBus modBus, ModContainer container) {
        if (!IndustrialMixinPlugin.ifPresent) {
            LOG.info("[Thread Tearer] industrial foregoing absent, addon idle");
            return;
        }
        if (IndustrialOffloadPolicy.disabled()) {
            LOG.info("[Thread Tearer] IF offload disabled (-Dthreadtearer.industrial.offload=false)");
            return;
        }
        LOG.info("[Thread Tearer] IF addon loaded, {} machines offloadable, runtime={}",
                IndustrialOffloadPolicy.allowedSize(), MCT.runtime().getClass().getSimpleName());
    }
}