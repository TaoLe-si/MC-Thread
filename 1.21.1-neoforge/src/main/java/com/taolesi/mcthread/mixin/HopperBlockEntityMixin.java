package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hopper tickers run on the compute pool. Slot writes go to interaction.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
    private static void mcthread$relocatePushItemsTick(Level level, BlockPos pos, BlockState state,
                                                       HopperBlockEntity hopper, CallbackInfo ci) {
        if (InteractionRelocator.stealTick(hopper, () -> HopperBlockEntity.pushItemsTick(level, pos, state, hopper))) {
            ci.cancel();
        }
    }

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private static void mcthread$relocateEntityInside(Level level, BlockPos pos, BlockState state,
                                                     Entity entity, HopperBlockEntity hopper, CallbackInfo ci) {
        if (InteractionRelocator.stealTick(hopper, () -> HopperBlockEntity.entityInside(level, pos, state, entity, hopper))) {
            ci.cancel();
        }
    }
}
