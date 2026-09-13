package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateInteract(Player player, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        Entity self = (Entity) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS, () -> self.interact(player, hand))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateInteractAt(Player player, Vec3 vec, InteractionHand hand,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        Entity self = (Entity) (Object) this;
        InteractionRelocator.stealReturning(InteractionResult.SUCCESS, () -> self.interactAt(player, vec, hand))
                .ifPresent(cir::setReturnValue);
    }
}
