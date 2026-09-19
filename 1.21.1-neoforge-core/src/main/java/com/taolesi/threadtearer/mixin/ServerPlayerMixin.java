package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "closeContainer", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateCloseContainer(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (InteractionRelocator.steal(self::closeContainer)) {
            ci.cancel();
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.hurt(source, amount))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (InteractionRelocator.steal(() -> self.die(source))) {
            ci.cancel();
        }
    }
}
