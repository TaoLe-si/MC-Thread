package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.Ae2GridGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "appeng.me.GridConnection", remap = false)
public abstract class Ae2GridConnectionMixin {

    @Inject(
            method = "create(Lappeng/api/networking/IGridNode;Lappeng/api/networking/IGridNode;Lnet/minecraft/core/Direction;)Lappeng/me/GridConnection;",
            at = @At("HEAD"))
    private static void mcthread$lockCreate(CallbackInfoReturnable<?> cir) {
        Ae2GridGuard.acquire();
    }

    @Inject(
            method = "create(Lappeng/api/networking/IGridNode;Lappeng/api/networking/IGridNode;Lnet/minecraft/core/Direction;)Lappeng/me/GridConnection;",
            at = @At("RETURN"))
    private static void mcthread$unlockCreate(CallbackInfoReturnable<?> cir) {
        Ae2GridGuard.release();
    }
}
