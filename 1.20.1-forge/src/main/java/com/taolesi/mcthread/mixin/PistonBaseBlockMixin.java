package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    @Inject(method = "triggerEvent", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateTrigger(BlockState state, Level level, BlockPos pos, int id, int param,
                                         CallbackInfoReturnable<Boolean> cir) {
        PistonBaseBlock self = (PistonBaseBlock) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.triggerEvent(state, level, pos, id, param))
                .ifPresent(cir::setReturnValue);
    }
}
