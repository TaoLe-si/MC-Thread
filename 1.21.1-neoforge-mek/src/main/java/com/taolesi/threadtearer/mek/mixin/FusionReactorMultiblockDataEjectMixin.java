package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Fusion reactor multiblock (MekanismGenerators): hand the world-reaching calls
 * in {@code FusionReactorMultiblockData.tick} to the server thread. The plasma
 * heating, D-T fuel injection, fuel burn, heat transfer and temperature
 * bookkeeping stay on the worker.
 *
 * <p>{@code CableUtils.emit} pushes plasma-as-energy into the neighbour ports
 * and {@code ChemicalUtil.emit} pushes steam to the turbines. {@code kill} is
 * the 1-in-20 AABB entity scan that damages everything inside the death zone for
 * 50000 — deferred whole, its own arithmetic is one modulo and one scan.
 *
 * <p>{@code vaporiseHohlraum} is deliberately <em>not</em> deferred: its only
 * capability lookup is on an {@code ItemStack}, which reads the static item
 * capability registry rather than the level.
 */
@Pseudo
@Mixin(targets = "mekanism.generators.common.content.fusion.FusionReactorMultiblockData", remap = false)
public abstract class FusionReactorMultiblockDataEjectMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/CableUtils;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;)V"),
            remap = false)
    private void mek$deferEnergyEmit(@Coerce Object targets, @Coerce Object container, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, container);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V"),
            remap = false)
    private void mek$deferSteamEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/generators/common/content/fusion/FusionReactorMultiblockData;kill"
                            + "(Lnet/minecraft/world/level/Level;)V"),
            remap = false)
    private void mek$deferKill(@Coerce Object self, @Coerce Object world, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, self, world);
    }
}
