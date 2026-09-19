package com.taolesi.threadtearer.industrial.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.industrial.IfDeferral;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Splits an offloaded Titanium tick so its neighbour auto-push stays on the
 * server thread.
 *
 * <p>{@code ActiveTile.serverTick} is two things in one method: the
 * progress-bar update — the recipe work, the expensive part, touching only
 * the machine's own state — and, every {@code getFacingHandlerWorkTime()}
 * ticks, the auto-push of every sided inventory and tank whose face is
 * enabled ({@code IFacingComponent.work}). The push resolves the neighbour's
 * item/fluid capability and writes into it; doing that from a worker is the
 * same hazard class as the Mekanism ejector push (an off-thread capability
 * write is the AE2-freeze class of bug).
 *
 * <p>There are two identical call sites — one over the inventory handlers,
 * one over the tanks — and they share a descriptor, so this wraps by
 * {@code ordinal = ALL}. Both discard the {@code boolean} return, so the
 * worker-side stub in {@link IfDeferral} returns {@code false} without
 * changing what the tick observes.
 *
 * <p>This mixin targets Titanium because that is where the call site lives;
 * the deferral only ever fires while a tick this addon's own policy admitted
 * is running on a worker, so other Titanium mods are unaffected.
 */
@Pseudo
@Mixin(targets = "com.hrznstudio.titanium.block.tile.ActiveTile", remap = false)
public abstract class ActiveTileFacingWorkMixin {

    @WrapOperation(
            method = "serverTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lcom/hrznstudio/titanium/block/tile/ActiveTile;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/hrznstudio/titanium/component/sideness/IFacingComponent;"
                            + "work(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/core/Direction;I)Z"),
            remap = false)
    private boolean industrial$deferFacingWork(@Coerce Object component, @Coerce Object level, @Coerce Object pos,
                                               @Coerce Object facing, int amount, Operation<Boolean> original) {
        return IfDeferral.runOrDefer(this, original, component, level, pos, facing, amount);
    }
}