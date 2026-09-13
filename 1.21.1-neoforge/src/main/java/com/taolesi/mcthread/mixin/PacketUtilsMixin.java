package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relocated packet handlers run on the interaction thread on purpose.
 * Skip the bounce-back to the server thread for those calls only.
 */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {

    @Inject(
            method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static <T extends PacketListener> void mcthread$allowOnServerLevel(
            Packet<T> packet, T listener, ServerLevel level, CallbackInfo ci) {
        if (InteractionRelocator.isInsideRelocatedVanilla()
                || InteractionRelocator.isOnInteractionThread()
                || InteractionRelocator.isOnTickThread()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static <T extends PacketListener> void mcthread$allowOnLoop(
            Packet<T> packet, T listener, BlockableEventLoop<?> loop, CallbackInfo ci) {
        if (InteractionRelocator.isInsideRelocatedVanilla()
                || InteractionRelocator.isOnInteractionThread()
                || InteractionRelocator.isOnTickThread()) {
            ci.cancel();
        }
    }
}
