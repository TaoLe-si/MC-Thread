package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * SPS: hand the two world-reaching calls in {@code SPSMultiblockData.tick} to
 * the server thread. The antimatter processing, progress accounting and the
 * coil data tick all stay on the worker.
 *
 * <p>{@code ChemicalUtil.emit} pushes the output tank into the neighbour SPS
 * ports. {@code kill} is the one that matters: on its 1-in-20 roll it scans an
 * AABB for entities, damages each one, fires {@code thunderHit} with a
 * lightning bolt, walks {@code ServerLevel.players()} and triggers an
 * advancement. None of that may run off the server thread.
 */
@Pseudo
@Mixin(targets = "mekanism.common.content.sps.SPSMultiblockData", remap = false)
public abstract class SPSMultiblockDataEjectMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V"),
            remap = false)
    private void mek$deferEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/content/sps/SPSMultiblockData;kill"
                            + "(Lnet/minecraft/world/level/Level;)V"),
            remap = false)
    private void mek$deferKill(@Coerce Object self, @Coerce Object world, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, self, world);
    }
}
