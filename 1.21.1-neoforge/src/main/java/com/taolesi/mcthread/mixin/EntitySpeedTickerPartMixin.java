package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ExtendedAE Plus extra-ticks the target on the server thread while the
 * vanilla ticker may still be running on the compute pool. Serialize those
 * two paths per block entity.
 */
@Pseudo
@Mixin(targets = "com.extendedae_plus.ae.parts.EntitySpeedTickerPart", remap = false)
public abstract class EntitySpeedTickerPartMixin {

    @Redirect(
            method = "performTicks(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/block/entity/BlockEntityTicker;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
                    remap = true))
    private void mcthread$lockAcceleratedTick(BlockEntityTicker<?> ticker, Level level, BlockPos pos,
                                              BlockState state, BlockEntity blockEntity) {
        InteractionRelocator.runLockedBlockEntityTick(blockEntity,
                () -> tickUnchecked(ticker, level, pos, state, blockEntity));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> void tickUnchecked(BlockEntityTicker<?> ticker, Level level, BlockPos pos,
                                                              BlockState state, BlockEntity blockEntity) {
        ((BlockEntityTicker<T>) ticker).tick(level, pos, state, (T) blockEntity);
    }
}
