package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.BlockEntityTickLock;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements BlockEntityTickLock {

    @Unique
    private final Object threadtearer$tickMutex = new Object();

    @Override
    public Object threadtearer$tickLock() {
        return this.threadtearer$tickMutex;
    }

    @Inject(method = "setChanged()V", at = @At("HEAD"), cancellable = true)
    private void threadtearer$setChanged(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        InteractionRelocator.stealAndCancel(ci, self::setChanged);
    }
}
