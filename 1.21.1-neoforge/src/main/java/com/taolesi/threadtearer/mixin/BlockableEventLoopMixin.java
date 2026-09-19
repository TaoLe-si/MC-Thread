package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps off-thread callers from inlining server work.
 *
 * <p>A previous revision also made {@code MinecraftServer.isSameThread()}
 * answer {@code true} for compute threads, to satisfy GregTech recipe IO. That
 * lie is gone: it told every mod that gates on {@code isSameThread()} that it
 * was running on the server thread, so their server-only paths ran inline on a
 * worker. AE2's {@code TickHandler} is the proof — its per-level
 * {@code ArrayDeque} ended up drained by two threads at once, producing
 * {@code isEmpty() == false} followed by {@code poll() == null}, and its catch
 * block logs and retries the loop, so the server thread logged 62k NPEs and
 * stalled for over a minute. GregTech support no longer exists in this
 * baseline, so the lie had no remaining beneficiary.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin<R extends Runnable> {

    @Shadow
    protected abstract Thread getRunningThread();

    @Shadow
    protected abstract R wrapRunnable(Runnable runnable);

    @Shadow
    public abstract void tell(R task);

    /**
     * Off-thread callers still must not run the task inline, even when the
     * server is stopping and vanilla's {@code execute} would do exactly that.
     */
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void threadtearer$queueWhenFakingSameThread(Runnable task, CallbackInfo ci) {
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
