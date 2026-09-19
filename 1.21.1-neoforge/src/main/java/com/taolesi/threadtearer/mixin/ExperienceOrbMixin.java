package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocatePlayerTouch(Player player, CallbackInfo ci) {
        ExperienceOrb self = (ExperienceOrb) (Object) this;
        if (InteractionRelocator.steal(() -> self.playerTouch(player))) {
            ci.cancel();
        }
    }
}
