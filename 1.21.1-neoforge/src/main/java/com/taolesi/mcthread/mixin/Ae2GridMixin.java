package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.Ae2GridGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.me.Grid", remap = false)
public abstract class Ae2GridMixin {

    @Inject(method = "add(Lappeng/me/GridNode;Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"))
    private void mcthread$lockAdd(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "add(Lappeng/me/GridNode;Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void mcthread$unlockAdd(CallbackInfo ci) {
        Ae2GridGuard.release();
    }
}
