package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Shadow
    protected abstract void dispenseFrom(ServerLevel level, BlockPos pos);

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateDispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (InteractionRelocator.steal(() -> this.dispenseFrom(level, pos))) {
            ci.cancel();
        }
    }
}
