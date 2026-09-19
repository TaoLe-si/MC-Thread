package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World writes spawned during block-entity compute go to the interaction FIFO.
 * World/entity ticks stay on the server thread.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void threadtearer$addFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE, () -> self.addFreshEntity(entity));
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void threadtearer$addWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE, () -> self.addWithUUID(entity));
    }

    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void threadtearer$tryAddFreshEntityWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        InteractionRelocator.stealAndReturn(cir, Boolean.TRUE, () -> self.tryAddFreshEntityWithPassengers(entity));
    }
}
