package com.taolesi.mcthread.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GTCEu {@code serverTicks} is an {@code ArrayList}. Vanilla compute ticks and
 * ExtendedAE Plus extra ticks must not iterate it at the same time.
 */
@Pseudo
@Mixin(targets = "com.gregtechceu.gtceu.api.machine.MetaMachine", remap = false)
public abstract class MetaMachineMixin {

    @Unique
    private static final ThreadLocal<Boolean> MCTHREAD$INSIDE_EXECUTE = ThreadLocal.withInitial(() -> false);

    @Inject(method = "executeTick()V", at = @At("HEAD"), cancellable = true)
    private void mcthread$serializeExecuteTick(CallbackInfo ci) {
        if (Boolean.TRUE.equals(MCTHREAD$INSIDE_EXECUTE.get())) {
            return;
        }
        ci.cancel();
        synchronized (this) {
            MCTHREAD$INSIDE_EXECUTE.set(true);
            try {
                mcthread$invokeExecuteTick();
            } finally {
                MCTHREAD$INSIDE_EXECUTE.remove();
            }
        }
    }

    @Invoker(value = "executeTick", remap = false)
    abstract void mcthread$invokeExecuteTick();
}
