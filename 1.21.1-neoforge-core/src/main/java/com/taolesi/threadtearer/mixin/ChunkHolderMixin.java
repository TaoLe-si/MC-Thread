package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Changed-block short sets are not thread-safe. Compute-thread ticks must not
 * record section updates while the server broadcasts them.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void threadtearer$blockChanged(BlockPos pos, CallbackInfo ci) {
        ChunkHolder self = (ChunkHolder) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.blockChanged(pos));
    }

    @Inject(method = "sectionLightChanged", at = @At("HEAD"), cancellable = true)
    private void threadtearer$sectionLightChanged(LightLayer type, int sectionY, CallbackInfo ci) {
        ChunkHolder self = (ChunkHolder) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.sectionLightChanged(type, sectionY));
    }
}
