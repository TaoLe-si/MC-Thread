package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.Ae2GridGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders", remap = false)
public abstract class Ae2NetworkCraftingProvidersMixin {

    @Inject(method = "addProvider", at = @At("HEAD"))
    private void mcthread$lockAddProvider(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "addProvider", at = @At("RETURN"))
    private void mcthread$unlockAddProvider(CallbackInfo ci) {
        Ae2GridGuard.release();
    }
}
