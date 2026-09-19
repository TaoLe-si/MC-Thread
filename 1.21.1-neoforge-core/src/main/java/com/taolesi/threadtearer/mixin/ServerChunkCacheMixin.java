package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compute-thread ticks may read already-loaded chunks. New loads still go
 * through the interaction FIFO via ticket writes.
 */
@Mixin(value = ServerChunkCache.class, priority = 1100)
public abstract class ServerChunkCacheMixin {

    @Shadow
    @Final
    private Thread mainThread;

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$allowChunkOnWorldThreads(int x, int z, ChunkStatus requiredStatus, boolean load,
                                                   CallbackInfoReturnable<ChunkAccess> cir) {
        if (!shouldReadChunkOnThisThread()) {
            return;
        }
        cir.setReturnValue(peekLoadedChunk(x, z));
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void threadtearer$allowChunkNowOnWorldThreads(int x, int z,
                                                      CallbackInfoReturnable<LevelChunk> cir) {
        if (!shouldReadChunkOnThisThread()) {
            return;
        }
        ChunkAccess chunk = peekLoadedChunk(x, z);
        cir.setReturnValue(chunk instanceof LevelChunk levelChunk ? levelChunk : null);
    }

    private boolean shouldReadChunkOnThisThread() {
        return InteractionRelocator.isWorldAccessorThread()
                && Thread.currentThread() != this.mainThread;
    }

    @Nullable
    private ChunkAccess peekLoadedChunk(int x, int z) {
        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }
        return holder.getTickingChunk();
    }
}
