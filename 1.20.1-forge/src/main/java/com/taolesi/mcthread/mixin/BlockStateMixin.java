package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chokepoint for every Block subclass (including overrides of {@code use}/{@code tick}).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUse(Level level, Player player, InteractionHand hand, BlockHitResult hit,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS,
                        () -> self.use(level, player, hand, hit))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateAttack(Level level, BlockPos pos, Player player, CallbackInfo ci) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        if (InteractionRelocator.steal(() -> self.attack(level, pos, player))) {
            ci.cancel();
        }
    }

    @Inject(method = "onProjectileHit", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateProjectile(Level level, net.minecraft.world.level.block.state.BlockState state,
                                            BlockHitResult hit, net.minecraft.world.entity.projectile.Projectile projectile,
                                            CallbackInfo ci) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        if (InteractionRelocator.steal(() -> self.onProjectileHit(level, state, hit, projectile))) {
            ci.cancel();
        }
    }

    @Inject(method = "triggerEvent", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateTrigger(Level level, BlockPos pos, int id, int param,
                                         CallbackInfoReturnable<Boolean> cir) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.triggerEvent(level, pos, id, param))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "spawnAfterBreak", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateSpawnAfterBreak(net.minecraft.server.level.ServerLevel level, BlockPos pos,
                                                 net.minecraft.world.item.ItemStack stack, boolean dropExperience,
                                                 CallbackInfo ci) {
        BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
        if (InteractionRelocator.steal(() -> self.spawnAfterBreak(level, pos, stack, dropExperience))) {
            ci.cancel();
        }
    }
}
