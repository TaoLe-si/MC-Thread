package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relocates bound block-entity tickers onto the compute pool. AE2 keeps the
 * vanilla ticker on the server thread; its grid already ticks there.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin implements TickingBlockEntity {

    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateTick(CallbackInfo ci) {
        TickingBlockEntity self = this;
        if (InteractionRelocator.stealTick(this.blockEntity, self::tick)) {
            ci.cancel();
        }
    }
}
