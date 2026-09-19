package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Only relocate CommonHooks methods that call back into stolen vanilla (toss →
 * {@code Player.drop}). Result-collecting events must finish before the caller continues.
 */
@Mixin(value = CommonHooks.class, remap = false)
public abstract class CommonHooksMixin {

    @Inject(method = "onPlayerTossEvent", at = @At("HEAD"), cancellable = true)
    private static void threadtearer$toss(Player player, ItemStack stack, boolean includeName,
                                      CallbackInfoReturnable<ItemEntity> cir) {
        InteractionRelocator.stealAndReturn(cir, null,
                () -> CommonHooks.onPlayerTossEvent(player, stack, includeName));
    }
}
