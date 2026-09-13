package com.taolesi.mcthread.mixin;

import com.mojang.datafixers.util.Either;
import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

@Mixin(value = ServerChunkCache.class, priority = 1100)
public abstract class ServerChunkCacheMixin {

    private static volatile Method lithiumGetChunkBlocking;
    private static volatile boolean lithiumResolved;

    @Shadow
    @Final
    private Thread mainThread;

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    /**
     * Compute-thread ticks read already-loaded chunks. Loading would add
     * tickets; those still go through the interaction FIFO.
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$allowChunkOnWorldThreads(int x, int z, ChunkStatus requiredStatus, boolean load,
                                                   CallbackInfoReturnable<ChunkAccess> cir) {
        if (!shouldReadChunkOnThisThread()) {
            return;
        }
        cir.setReturnValue(peekLoadedChunk(x, z, requiredStatus));
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void mcthread$allowChunkNowOnWorldThreads(int x, int z,
                                                      CallbackInfoReturnable<LevelChunk> cir) {
        if (!shouldReadChunkOnThisThread()) {
            return;
        }
        ChunkAccess chunk = peekLoadedChunk(x, z, ChunkStatus.FULL);
        cir.setReturnValue(chunk instanceof LevelChunk levelChunk ? levelChunk : null);
    }

    private boolean shouldReadChunkOnThisThread() {
        return InteractionRelocator.isWorldAccessorThread()
                && Thread.currentThread() != this.mainThread;
    }

    @Nullable
    private ChunkAccess peekLoadedChunk(int x, int z, ChunkStatus requiredStatus) {
        ChunkAccess lithium = tryLithiumBlocking(x, z, requiredStatus);
        if (lithium != null) {
            return lithium;
        }
        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }
        if (requiredStatus.isOrAfter(ChunkStatus.FULL)) {
            LevelChunk ticking = holder.getTickingChunk();
            if (ticking != null) {
                return ticking;
            }
            LevelChunk full = holder.getFullChunk();
            if (full != null) {
                return full;
            }
        }
        ChunkAccess last = holder.getLastAvailable();
        if (last != null) {
            return last;
        }
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either =
                holder.getFutureIfPresentUnchecked(requiredStatus).getNow(ChunkHolder.UNLOADED_CHUNK);
        return either.left().orElse(null);
    }

    @Nullable
    private ChunkAccess tryLithiumBlocking(int x, int z, ChunkStatus requiredStatus) {
        Method method = lithiumBlocking();
        if (method == null) {
            return null;
        }
        try {
            return (ChunkAccess) method.invoke(this, x, z, requiredStatus, Boolean.FALSE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method lithiumBlocking() {
        if (lithiumResolved) {
            return lithiumGetChunkBlocking;
        }
        synchronized (ServerChunkCacheMixin.class) {
            if (!lithiumResolved) {
                try {
                    Method method = ServerChunkCache.class.getDeclaredMethod(
                            "getChunkBlocking", int.class, int.class, ChunkStatus.class, boolean.class);
                    method.setAccessible(true);
                    lithiumGetChunkBlocking = method;
                } catch (NoSuchMethodException ignored) {
                    lithiumGetChunkBlocking = null;
                }
                lithiumResolved = true;
            }
        }
        return lithiumGetChunkBlocking;
    }
}
