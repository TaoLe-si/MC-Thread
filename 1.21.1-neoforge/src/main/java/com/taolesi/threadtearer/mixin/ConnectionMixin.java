package com.taolesi.threadtearer.mixin;

import com.taolesi.threadtearer.experiment.InteractionDispatch;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;

/**
 * Hold packets until the current interaction request finishes computing, then
 * flush them in order (open-screen, block updates, AE2 channel packets, …).
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void threadtearer$deferSend(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (InteractionDispatch.defer(packet.getClass().getName(), () -> self.send(packet, listener))) {
            ci.cancel();
        }
    }
}
