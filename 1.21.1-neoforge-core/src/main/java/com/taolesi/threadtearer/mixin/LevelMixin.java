package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jetbrains.annotations.Nullable;
import java.util.Collection;

/**
 * World mutation APIs. Compute-thread block-entity ticks run interactions live.
 * Ticket writes still go to the interaction FIFO.
 *
 * <p>Which injections here actually fire on a server: {@code ServerLevel}
 * overrides {@code updateNeighborsAt} / {@code updateNeighborsAtExceptFromFacing}
 * without calling {@code super}, so those two do not; it inherits
 * {@code blockEntityChanged} and {@code updateNeighbourForOutputSignal}
 * unchanged, so those two do. That split is why the offloaded-worker
 * bookkeeping flood comes through the latter pair, and why batching them is
 * what removes it.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Shadow
    private Thread thread;

    /**
     * Compute-thread ticks must see {@code Level.thread} as themselves so
     * {@code getBlockEntity} and the rest of the live world APIs work.
     */
    @Redirect(method = "*", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/Level;thread:Ljava/lang/Thread;",
            opcode = Opcodes.GETFIELD))
    private Thread threadtearer$allowWorldOnComputeThreads(Level instance) {
        if (InteractionRelocator.isWorldAccessorThread()) {
            return Thread.currentThread();
        }
        return this.thread;
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                   CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        // Deferred writes (queued from a compute/tick worker, or through the
        // interaction FIFO) execute at a later tick boundary. By then the
        // target chunk may have unloaded. Touching it then makes vanilla call
        // getChunkBlocking — a synchronous chunk load from disk on the server
        // thread, which is what the watchdog caught as multi-second stalls.
        // isLoaded is a non-blocking lookup, so skip instead of stalling.
        if (InteractionRelocator.isInsideRelocatedVanilla() && !self.isLoaded(pos)) {
            cir.setReturnValue(false);
            return;
        }
        // Short-circuit no-op writes: Mekanism re-applies the same BlockState
        // every tick from its bounding-block / ejector / comparator code paths.
        // The read is guarded too: on a compute worker an unloaded chunk would
        // block that worker on a chunk load.
        if (self.isLoaded(pos) && self.getBlockState(pos) == state) {
            com.taolesi.threadtearer.monitor.SetBlockCounters.recordNoOp();
            cir.setReturnValue(false);
            return;
        }
        com.taolesi.threadtearer.monitor.SetBlockCounters.recordReal();
        // Compute/tick-worker light write: the expensive part of a vanilla
        // setBlock is not the chunk-section write (~tens of µs) but the
        // per-write fan-out: one ClientboundBlockUpdatePacket per watching
        // player, six immediate neighbourChanged calls (each may re-check
        // redstone on machine neighbours) and comparator/shape cascades.
        // The light path writes the section + block entity with flags=0
        // (the same semantics vanilla worldgen uses), marks the section dirty
        // so vanilla's ChunkHolder sends ONE batched section-update packet
        // per chunk per tick, and defers the neighbour notification to the
        // tick boundary via PositionUpdateBatch (deduped). Server-thread
        // callers keep the full immediate vanilla semantics.
        if (InteractionRelocator.isComputing() || InteractionRelocator.isOnTickThread()) {
            com.taolesi.threadtearer.runtime.MCTRuntimeImpl rt =
                    com.taolesi.threadtearer.runtime.MCTRuntimeImpl.get();
            if (rt != null && rt.server() != null && !rt.isServerThread()) {
                rt.runOnServerThread(() -> {
                    if (!self.isLoaded(pos)) {
                        return;
                    }
                    // flags=0 keeps the chunk-section write and the block-entity
                    // swap (LevelChunk.setBlockState handles those regardless
                    // of flags) and skips the per-write fan-out.
                    boolean changed = self.setBlock(pos, state, 0, recursionLeft);
                    if (changed && self instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                        serverLevel.getChunkSource().blockChanged(pos);
                        // The chunk marks itself unsaved inside setBlockState,
                        // so only the neighbour and comparator notifications are
                        // owed here — and vanilla gates the comparator one on
                        // the new state carrying an analog signal.
                        com.taolesi.threadtearer.runtime.PositionUpdateBatch.record(
                                serverLevel, pos, true, state.hasAnalogOutputSignal(), false);
                    }
                });
                cir.setReturnValue(true);
                return;
            }
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE,
                () -> self.setBlock(pos, state, flags, recursionLeft));
    }

    @Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
    private void threadtearer$removeBlock(BlockPos pos, boolean isMoving, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (threadtearer$deferredIntoUnloadedChunk(self, pos)) {
            cir.setReturnValue(false);
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE, () -> self.removeBlock(pos, isMoving));
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft,
                                       CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (threadtearer$deferredIntoUnloadedChunk(self, pos)) {
            cir.setReturnValue(false);
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE,
                () -> self.destroyBlock(pos, dropBlock, entity, recursionLeft));
    }

    /**
     * True when this call is running as a deferred apply and the target chunk
     * has since unloaded. Touching it then makes vanilla call
     * {@code ServerChunkCache.getChunkBlocking} — a synchronous disk load on
     * the server thread, which is what the watchdog caught as multi-second
     * stalls. {@code isLoaded} is non-blocking, so skipping is cheap and
     * strictly safer than stalling the tick loop.
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean threadtearer$deferredIntoUnloadedChunk(Level level, BlockPos pos) {
        return InteractionRelocator.isInsideRelocatedVanilla() && !level.isLoaded(pos);
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void threadtearer$setBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.setBlockEntity(blockEntity));
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void threadtearer$removeBlockEntity(BlockPos pos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (threadtearer$deferredIntoUnloadedChunk(self, pos)) {
            ci.cancel();
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.removeBlockEntity(pos));
    }

    /**
     * Offloaded workers fold this into {@link PositionUpdateBatch} instead of
     * queueing a deferred apply task. The body is one line —
     * {@code chunk.setUnsaved(true)} — and it is emitted by every
     * {@code BlockEntity.setChanged()}, which Titanium's progress bar calls
     * unconditionally from {@code serverTick} every tick. As a per-call
     * deferred task this alone was several thousand no-op applies per tick.
     */
    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void threadtearer$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (InteractionRelocator.shouldBatchWorldBookkeeping()) {
            com.taolesi.threadtearer.runtime.PositionUpdateBatch.record(self, pos, false, false, true);
            ci.cancel();
            return;
        }
        if (threadtearer$deferredIntoUnloadedChunk(self, pos)) {
            ci.cancel();
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.blockEntityChanged(pos));
    }

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true, remap = false)
    private void threadtearer$addFreshBlockEntities(Collection<BlockEntity> blockEntities, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.addFreshBlockEntities(blockEntities));
    }

    /**
     * After the owner places, neighbor notification is a second request:
     * interaction computes, then the owner applies {@code updateNeighborsAt}.
     *
     * <p>Offloaded workers fold it into {@link PositionUpdateBatch} — one
     * notification per position per tick, which is what the batching was
     * introduced for in the first place.
     *
     * <p>Note this injects into {@code Level}'s own body, and
     * {@code ServerLevel} overrides the method without calling {@code super}
     * (it goes straight to {@code neighborUpdater}). On a server this injection
     * therefore does not fire at all; the batching that actually matters is the
     * explicit {@code PositionUpdateBatch.record(…, notifyNeighbours=true, …)}
     * in the {@code setBlock} light path above. Kept for any non-{@code
     * ServerLevel} {@code Level} that does delegate here.
     */
    @Inject(method = "updateNeighborsAt", at = @At("HEAD"), cancellable = true)
    private void threadtearer$neighborUpdate(BlockPos pos, Block block, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (InteractionRelocator.shouldBatchWorldBookkeeping()) {
            com.taolesi.threadtearer.runtime.PositionUpdateBatch.record(self, pos, true, false, false);
            ci.cancel();
            return;
        }
        if (InteractionRelocator.steal(() -> self.updateNeighborsAt(pos, block))) {
            ci.cancel();
        }
    }

    /**
     * Deliberately NOT batched. The batch's neighbour flag replays
     * {@code updateNeighborsAt}, which notifies all six neighbours, whereas
     * this variant excludes one facing — folding them together would notify a
     * neighbour vanilla leaves alone.
     *
     * <p>Like the method above, this is {@code Level}'s own (empty) body, which
     * {@code ServerLevel} overrides without calling {@code super}, so on a
     * server it never fires either. The distinction is preserved rather than
     * relied on: a future change that routes the real path through here must
     * not silently gain six-way notification.
     */
    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void threadtearer$neighborUpdateExceptFacing(BlockPos pos, Block block, Direction facing, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (InteractionRelocator.steal(() -> self.updateNeighborsAtExceptFromFacing(pos, block, facing))) {
            ci.cancel();
        }
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$neighborChanged(BlockPos pos, Block block, BlockPos fromPos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.neighborChanged(pos, block, fromPos));
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$neighborChangedState(BlockState state, BlockPos pos, Block block, BlockPos fromPos,
                                               boolean isMoving, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.neighborChanged(state, pos, block, fromPos, isMoving));
    }

    @Inject(method = "neighborShapeChanged", at = @At("HEAD"), cancellable = true)
    private void threadtearer$neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos,
                                               int flags, int recursionLevel, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci,
                () -> self.neighborShapeChanged(direction, queried, pos, offsetPos, flags, recursionLevel));
    }

    /**
     * Offloaded workers fold this into {@link PositionUpdateBatch}: the
     * comparator fan-out is idempotent and keyed purely by position, and it is
     * the other half of every {@code setChanged()} — the {@code block} argument
     * is re-read at flush time, so a worker's stale value cannot leak through.
     */
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void threadtearer$updateNeighbourForOutputSignal(BlockPos pos, Block block, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (InteractionRelocator.shouldBatchWorldBookkeeping()) {
            com.taolesi.threadtearer.runtime.PositionUpdateBatch.record(self, pos, false, true, false);
            ci.cancel();
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.updateNeighbourForOutputSignal(pos, block));
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void threadtearer$blockEvent(BlockPos pos, Block block, int eventId, int eventParam, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.blockEvent(pos, block, eventId, eventParam));
    }

    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateExplode(@Nullable Entity source, @Nullable DamageSource damageSource,
                                          @Nullable ExplosionDamageCalculator damageCalculator,
                                          double x, double y, double z, float radius, boolean fire,
                                          Level.ExplosionInteraction interaction,
                                          CallbackInfoReturnable<Explosion> cir) {
        Level self = (Level) (Object) this;
        InteractionRelocator.stealAndReturn(cir, null,
                () -> self.explode(source, damageSource, damageCalculator, x, y, z, radius, fire, interaction));
    }

    /**
     * Combined set-block + neighbour-update that vanilla exposes as a
     * convenience for {@link net.minecraft.world.level.block.BaseFireBlock}
     * and similar blocks. Off-thread calls (compute / tick) are routed back
     * to the server thread FIFO so the resulting packet fan-out does not
     * race with player interactions.
     *
     * <p>Signature verified against MC 1.21.1 mappings:
     * {@code boolean setBlockAndUpdate(BlockPos, BlockState)} — returns the
     * OR of {@code setBlock} and {@code sendBlockUpdated} results.
     *
     * <p>Note: {@code sendBlockUpdated} is declared abstract on {@code Level}
     * and only concretely defined on {@code ClientLevel} and {@code ServerLevel}.
     * Its inline call inside this method runs on whatever thread
     * {@code setBlockAndUpdate} itself runs on, so routing this method is
     * sufficient — the packet fan-out inherits the same FIFO ordering.
     */
    @Inject(method = "setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$setBlockAndUpdate(BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE,
                () -> self.setBlockAndUpdate(pos, state));
    }
}
