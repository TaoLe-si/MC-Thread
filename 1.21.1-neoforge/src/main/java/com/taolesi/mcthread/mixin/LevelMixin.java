package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
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
    private Thread mcthread$allowWorldOnComputeThreads(Level instance) {
        if (InteractionRelocator.isWorldAccessorThread()) {
            return Thread.currentThread();
        }
        return this.thread;
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                   CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE,
                () -> self.setBlock(pos, state, flags, recursionLeft));
    }

    @Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
    private void mcthread$removeBlock(BlockPos pos, boolean isMoving, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE, () -> self.removeBlock(pos, isMoving));
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft,
                                       CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE,
                () -> self.destroyBlock(pos, dropBlock, entity, recursionLeft));
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void mcthread$setBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.setBlockEntity(blockEntity));
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void mcthread$removeBlockEntity(BlockPos pos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.removeBlockEntity(pos));
    }

    @Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
    private void mcthread$blockEntityChanged(BlockPos pos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.blockEntityChanged(pos));
    }

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true, remap = false)
    private void mcthread$addFreshBlockEntities(Collection<BlockEntity> blockEntities, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.addFreshBlockEntities(blockEntities));
    }

    /**
     * After the owner places, neighbor notification is a second request:
     * interaction computes, then the owner applies {@code updateNeighborsAt}.
     */
    @Inject(method = "updateNeighborsAt", at = @At("HEAD"), cancellable = true)
    private void mcthread$neighborUpdate(BlockPos pos, Block block, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        if (InteractionRelocator.steal(() -> self.updateNeighborsAt(pos, block))) {
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void mcthread$neighborUpdateExceptFacing(BlockPos pos, Block block, Direction facing, CallbackInfo ci) {
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
    private void mcthread$neighborChanged(BlockPos pos, Block block, BlockPos fromPos, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.neighborChanged(pos, block, fromPos));
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$neighborChangedState(BlockState state, BlockPos pos, Block block, BlockPos fromPos,
                                               boolean isMoving, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.neighborChanged(state, pos, block, fromPos, isMoving));
    }

    @Inject(method = "neighborShapeChanged", at = @At("HEAD"), cancellable = true)
    private void mcthread$neighborShapeChanged(Direction direction, BlockState queried, BlockPos pos, BlockPos offsetPos,
                                               int flags, int recursionLevel, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci,
                () -> self.neighborShapeChanged(direction, queried, pos, offsetPos, flags, recursionLevel));
    }

    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
    private void mcthread$updateNeighbourForOutputSignal(BlockPos pos, Block block, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.updateNeighbourForOutputSignal(pos, block));
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void mcthread$blockEvent(BlockPos pos, Block block, int eventId, int eventParam, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (self.isClientSide) {
            return;
        }
        InteractionRelocator.stealAndCancel(ci, () -> self.blockEvent(pos, block, eventId, eventParam));
    }

    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateExplode(@Nullable Entity source, @Nullable DamageSource damageSource,
                                          @Nullable ExplosionDamageCalculator damageCalculator,
                                          double x, double y, double z, float radius, boolean fire,
                                          Level.ExplosionInteraction interaction,
                                          CallbackInfoReturnable<Explosion> cir) {
        Level self = (Level) (Object) this;
        InteractionRelocator.stealAndReturn(cir, null,
                () -> self.explode(source, damageSource, damageCalculator, x, y, z, radius, fire, interaction));
    }
}
