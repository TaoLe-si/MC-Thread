package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerBlockEntityMixin {

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$setItem(int slot, ItemStack stack, CallbackInfo ci) {
        RandomizableContainerBlockEntity self = (RandomizableContainerBlockEntity) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.setItem(slot, stack));
    }

    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$removeItem(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        RandomizableContainerBlockEntity self = (RandomizableContainerBlockEntity) (Object) this;
        InteractionRelocator.stealAndReturn(cir, ItemStack.EMPTY, () -> self.removeItem(slot, amount));
    }
}
