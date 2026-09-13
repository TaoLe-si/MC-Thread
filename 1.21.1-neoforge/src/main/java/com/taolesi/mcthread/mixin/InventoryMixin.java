package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$setItem(int slot, ItemStack stack, CallbackInfo ci) {
        Inventory self = (Inventory) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> self.setItem(slot, stack));
    }

    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private void mcthread$removeItem(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        Inventory self = (Inventory) (Object) this;
        InteractionRelocator.stealAndReturn(cir, ItemStack.EMPTY, () -> self.removeItem(slot, amount));
    }
}
