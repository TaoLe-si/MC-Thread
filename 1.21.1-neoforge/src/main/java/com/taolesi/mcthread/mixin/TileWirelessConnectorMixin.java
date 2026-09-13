package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.Ae2GridGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ExtendedAE wireless connectors merge AE2 grids. Keep that tick atomic
 * against other threads calling {@code Grid.add}.
 */
@Pseudo
@Mixin(targets = "com.glodblock.github.extendedae.common.tileentities.TileWirelessConnector", remap = false)
public abstract class TileWirelessConnectorMixin {

    @Inject(method = "serverTick()V", at = @At("HEAD"), cancellable = true)
    private void mcthread$serializeWirelessTick(CallbackInfo ci) {
        if (Ae2GridGuard.heldByCurrent()) {
            return;
        }
        ci.cancel();
        Ae2GridGuard.run(this::mcthread$invokeServerTick);
    }

    @Invoker(value = "serverTick", remap = false)
    abstract void mcthread$invokeServerTick();
}
