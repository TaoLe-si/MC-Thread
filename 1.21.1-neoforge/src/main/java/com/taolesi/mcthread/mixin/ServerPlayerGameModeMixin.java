package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Relocates player-block / player-item use onto the interaction thread.
 * Other Mixins on these methods run with the vanilla body.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUseItemOn(ServerPlayer player, Level level, ItemStack stack,
                                           InteractionHand hand, BlockHitResult hitResult,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS,
                        () -> self.useItemOn(player, level, stack, hand, hitResult))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUseItem(ServerPlayer player, Level level, ItemStack stack,
                                         InteractionHand hand,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS,
                        () -> self.useItem(player, level, stack, hand))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateDestroyBlock(net.minecraft.core.BlockPos pos,
                                              CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.destroyBlock(pos))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateBreakAction(BlockPos pos, ServerboundPlayerActionPacket.Action action,
                                             Direction direction, int maxHeight, int sequence,
                                             CallbackInfo ci) {
        ServerPlayerGameMode self = (ServerPlayerGameMode) (Object) this;
        if (InteractionRelocator.steal(() -> self.handleBlockBreakAction(pos, action, direction, maxHeight, sequence))) {
            ci.cancel();
        }
    }
}
