package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GregTech recipe IO checks {@code MinecraftServer.isSameThread()}. Compute-thread
 * block-entity ticks answer true for those checks. {@code execute} still queues
 * onto the real server thread so apply never inlines on a worker.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin<R extends Runnable> {

    @Shadow
    protected abstract Thread getRunningThread();

    @Shadow
    protected abstract R wrapRunnable(Runnable runnable);

    @Shadow
    public abstract void tell(R task);

    @Inject(method = "isSameThread", at = @At("HEAD"), cancellable = true)
    private void mcthread$computeIsSameThread(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof MinecraftServer)) {
            return;
        }
        if (InteractionRelocator.isComputing()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void mcthread$queueWhenFakingSameThread(Runnable task, CallbackInfo ci) {
        if (!((Object) this instanceof MinecraftServer)) {
            return;
        }
        if (Thread.currentThread() == this.getRunningThread()) {
            return;
        }
        if (!InteractionRelocator.isComputing()) {
            return;
        }
        this.tell(this.wrapRunnable(task));
        ci.cancel();
    }
}
