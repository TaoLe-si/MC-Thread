package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateExplode(CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        if (InteractionRelocator.steal(self::explode)) {
            ci.cancel();
        }
    }

    @Inject(method = "finalizeExplosion", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateFinalize(boolean spawnParticles, CallbackInfo ci) {
        Explosion self = (Explosion) (Object) this;
        if (InteractionRelocator.steal(() -> self.finalizeExplosion(spawnParticles))) {
            ci.cancel();
        }
    }
}
