package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateInteractOn(Entity entity, InteractionHand hand,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        Player self = (Player) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS, () -> self.interactOn(entity, hand))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateAttack(Entity target, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (InteractionRelocator.steal(() -> self.attack(target))) {
            ci.cancel();
        }
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership,
                                      CallbackInfoReturnable<ItemEntity> cir) {
        Player self = (Player) (Object) this;
        InteractionRelocator.stealAndReturn(cir, null,
                () -> self.drop(stack, throwRandomly, retainOwnership));
    }
}
