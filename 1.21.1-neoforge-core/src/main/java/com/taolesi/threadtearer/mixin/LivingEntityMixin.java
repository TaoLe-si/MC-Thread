package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Shadow
    protected abstract void actuallyHurt(DamageSource source, float amount);

    @Shadow
    protected abstract void completeUsingItem();

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        if (InteractionRelocator.steal(() -> this.actuallyHurt(source, amount))) {
            ci.cancel();
        }
    }

    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateCompleteUsing(CallbackInfo ci) {
        if (InteractionRelocator.steal(this::completeUsingItem)) {
            ci.cancel();
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateReleaseUsing(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (InteractionRelocator.steal(self::releaseUsingItem)) {
            ci.cancel();
        }
    }

    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateKnockback(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (InteractionRelocator.steal(() -> self.knockback(strength, x, z))) {
            ci.cancel();
        }
    }


    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.hurt(source, amount))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (InteractionRelocator.steal(() -> self.die(source))) {
            ci.cancel();
        }
    }
}
