package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {

    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private static void mcthread$relocateServerTick(Level level, BlockPos pos, BlockState state,
                                                    BrewingStandBlockEntity brewing, CallbackInfo ci) {
        if (InteractionRelocator.stealTick(brewing, () -> BrewingStandBlockEntity.serverTick(level, pos, state, brewing))) {
            ci.cancel();
        }
    }

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$setItem(int slot, ItemStack stack, CallbackInfo ci) {
        BrewingStandBlockEntity self = (BrewingStandBlockEntity) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.setItem(slot, stack));
    }

    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$removeItem(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        BrewingStandBlockEntity self = (BrewingStandBlockEntity) (Object) this;
        InteractionRelocator.stealAndReturn(cir, ItemStack.EMPTY, () -> self.removeItem(slot, amount));
    }
}
