package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "respawn", at = @At("HEAD"), cancellable = true)
    private void mcthread$relocateRespawn(ServerPlayer player, boolean conqueredEnd,
                                         CallbackInfoReturnable<ServerPlayer> cir) {
        PlayerList self = (PlayerList) (Object) this;
        InteractionRelocator.stealReturning(player, () -> self.respawn(player, conqueredEnd))
                .ifPresent(cir::setReturnValue);
    }
}
