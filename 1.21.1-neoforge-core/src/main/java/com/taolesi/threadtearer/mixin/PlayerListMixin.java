package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "respawn", at = @At("HEAD"), cancellable = true)
    private void threadtearer$relocateRespawn(ServerPlayer player, boolean keepData, Entity.RemovalReason reason,
                                         CallbackInfoReturnable<ServerPlayer> cir) {
        PlayerList self = (PlayerList) (Object) this;
        InteractionRelocator.stealReturning(player, () -> self.respawn(player, keepData, reason))
                .ifPresent(cir::setReturnValue);
    }
}
