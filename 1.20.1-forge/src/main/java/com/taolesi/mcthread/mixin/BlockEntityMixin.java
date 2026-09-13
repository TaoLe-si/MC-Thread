package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.BlockEntityTickLock;
import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements BlockEntityTickLock {

    @Unique
    private final Object mcthread$tickMutex = new Object();

    @Override
    public Object mcthread$tickLock() {
        return this.mcthread$tickMutex;
    }

    @Inject(method = "setChanged()V", at = @At("HEAD"), cancellable = true)
    private void mcthread$setChanged(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        InteractionRelocator.stealAndCancel(ci, self::setChanged);
    }
}
