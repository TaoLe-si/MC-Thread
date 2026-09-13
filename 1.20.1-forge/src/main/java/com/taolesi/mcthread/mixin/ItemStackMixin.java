package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chokepoint for every Item subclass ({@code useOn}/{@code use}/entity interact).
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack self = (ItemStack) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS, () -> self.useOn(context))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateUse(Level level, Player player, InteractionHand hand,
                                     CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack self = (ItemStack) (Object) this;
        InteractionRelocator.stealReturning(InteractionResultHolder.pass(self), () -> self.use(level, player, hand))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateInteractLiving(Player player, LivingEntity target, InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack self = (ItemStack) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS,
                        () -> self.interactLivingEntity(player, target, hand))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateFinishUsing(Level level, LivingEntity entity,
                                             CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack) (Object) this;
        InteractionRelocator.stealReturning(self, () -> self.finishUsingItem(level, entity))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateReleaseUsing(Level level, LivingEntity entity, int timeLeft, CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (InteractionRelocator.steal(() -> self.releaseUsing(level, entity, timeLeft))) {
            ci.cancel();
        }
    }

    @Inject(method = "hurtAndBreak", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateHurtAndBreak(int amount, LivingEntity entity,
                                              java.util.function.Consumer<LivingEntity> onBroken,
                                              CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (InteractionRelocator.steal(() -> self.hurtAndBreak(amount, entity, onBroken))) {
            ci.cancel();
        }
    }
}
