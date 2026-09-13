package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.experiment.InteractionRelocator;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticket mutation follows the same path as {@code setBlock}: compute enqueues
 * to the interaction FIFO, then the owner thread applies it live.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {

    @Inject(method = "addTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$addTicket(TicketType<?> type, ChunkPos pos, int level, Object value, CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> addTicketRaw(self, type, pos, level, value));
    }

    @Inject(method = "removeTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$removeTicket(TicketType<?> type, ChunkPos pos, int level, Object value, CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> removeTicketRaw(self, type, pos, level, value));
    }

    @Inject(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$addRegionTicket(TicketType<?> type, ChunkPos pos, int distance, Object value,
                                          CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> addRegionTicketRaw(self, type, pos, distance, value, false, false));
    }

    @Inject(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mcthread$addRegionTicketForced(TicketType<?> type, ChunkPos pos, int distance, Object value,
                                                boolean forceTicks, CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> addRegionTicketRaw(self, type, pos, distance, value, true, forceTicks));
    }

    @Inject(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private void mcthread$removeRegionTicket(TicketType<?> type, ChunkPos pos, int distance, Object value,
                                             CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> removeRegionTicketRaw(self, type, pos, distance, value, false, false));
    }

    @Inject(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mcthread$removeRegionTicketForced(TicketType<?> type, ChunkPos pos, int distance, Object value,
                                                   boolean forceTicks, CallbackInfo ci) {
        DistanceManager self = (DistanceManager) (Object) this;
        InteractionRelocator.stealAndCancel(ci, () -> removeRegionTicketRaw(self, type, pos, distance, value, true, forceTicks));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addTicketRaw(DistanceManager self, TicketType type, ChunkPos pos, int level, Object value) {
        self.addTicket(type, pos, level, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeTicketRaw(DistanceManager self, TicketType type, ChunkPos pos, int level, Object value) {
        self.removeTicket(type, pos, level, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addRegionTicketRaw(DistanceManager self, TicketType type, ChunkPos pos, int distance,
                                           Object value, boolean withForce, boolean forceTicks) {
        if (withForce) {
            self.addRegionTicket(type, pos, distance, value, forceTicks);
        } else {
            self.addRegionTicket(type, pos, distance, value);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeRegionTicketRaw(DistanceManager self, TicketType type, ChunkPos pos, int distance,
                                              Object value, boolean withForce, boolean forceTicks) {
        if (withForce) {
            self.removeRegionTicket(type, pos, distance, value, forceTicks);
        } else {
            self.removeRegionTicket(type, pos, distance, value);
        }
    }
}
