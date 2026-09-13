package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Only relocate ForgeHooks methods that call back into stolen vanilla (toss →
 * {@code Player.drop}). Cancellable/result events such as right-click and
 * {@code AttachCapabilitiesEvent} must finish before the caller continues.
 */
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class ForgeHooksMixin {

    @Inject(method = "onPlayerTossEvent", at = @At("HEAD"), cancellable = true)
    private static void mcthread$toss(Player player, ItemStack stack, boolean includeName,
                                      CallbackInfoReturnable<ItemEntity> cir) {
        InteractionRelocator.stealAndReturn(cir, null,
                () -> ForgeHooks.onPlayerTossEvent(player, stack, includeName));
    }
}
