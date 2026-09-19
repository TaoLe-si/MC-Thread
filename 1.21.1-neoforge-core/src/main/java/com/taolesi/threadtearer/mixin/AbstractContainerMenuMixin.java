package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Relocates menu click / button methods so other mods' Mixins on
 * {@code clicked} / {@code clickMenuButton} run on the interaction thread.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateClicked(int slotId, int button, ClickType clickType, Player player,
                                         CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (InteractionRelocator.steal(() -> self.clicked(slotId, button, clickType, player))) {
            ci.cancel();
        }
    }

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateButton(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        InteractionRelocator.stealReturning(Boolean.TRUE, () -> self.clickMenuButton(player, id))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateRemoved(Player player, CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (InteractionRelocator.steal(() -> self.removed(player))) {
            ci.cancel();
        }
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateBroadcast(CallbackInfo ci) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (InteractionRelocator.steal(self::broadcastChanges)) {
            ci.cancel();
        }
    }
}
