package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.Ae2GridGuard;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2's tick PriorityQueue is not thread-safe. Compute-thread grid node
 * add/remove racing {@code peek}/{@code poll} throws IllegalStateException.
 */
@Pseudo
@Mixin(targets = "appeng.me.service.TickManagerService", remap = false)
public abstract class Ae2TickManagerServiceMixin {

    @Inject(method = "tickLevelQueue", at = @At("HEAD"), cancellable = true)
    private void mcthread$serializeTickLevelQueue(Level level, CallbackInfo ci) {
        if (Ae2GridGuard.heldByCurrent()) {
            return;
        }
        ci.cancel();
        Ae2GridGuard.run(() -> mcthread$invokeTickLevelQueue(level));
    }

    @Invoker(value = "tickLevelQueue", remap = false)
    abstract void mcthread$invokeTickLevelQueue(Level level);

    @Inject(method = "addToQueue", at = @At("HEAD"))
    private void mcthread$lockAddToQueue(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "addToQueue", at = @At("RETURN"))
    private void mcthread$unlockAddToQueue(CallbackInfo ci) {
        Ae2GridGuard.release();
    }

    @Inject(method = "removeFromQueue", at = @At("HEAD"))
    private void mcthread$lockRemoveFromQueue(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "removeFromQueue", at = @At("RETURN"))
    private void mcthread$unlockRemoveFromQueue(CallbackInfo ci) {
        Ae2GridGuard.release();
    }

    @Inject(method = "addNode", at = @At("HEAD"))
    private void mcthread$lockAddNode(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "addNode", at = @At("RETURN"))
    private void mcthread$unlockAddNode(CallbackInfo ci) {
        Ae2GridGuard.release();
    }

    @Inject(method = "removeNode", at = @At("HEAD"))
    private void mcthread$lockRemoveNode(CallbackInfo ci) {
        Ae2GridGuard.acquire();
    }

    @Inject(method = "removeNode", at = @At("RETURN"))
    private void mcthread$unlockRemoveNode(CallbackInfo ci) {
        Ae2GridGuard.release();
    }
}
