package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Fission reactor multiblock (MekanismGenerators): hand the world-reaching calls
 * in {@code FissionReactorMultiblockData.tick} and {@code burnFuel} to the
 * server thread. The fuel arithmetic, the coolant loop, the heat capacitor
 * update and the environment loss all stay on the worker.
 *
 * <p>Four kinds of call move:
 *
 * <ul>
 *   <li>The coolant and waste pushes into the neighbour ports. They share a
 *       descriptor, so {@code ordinal} 0 and 1 pin them in method order.</li>
 *   <li>{@code IRadiationManager.radiate} inside {@code burnFuel}, on the
 *       leftover-waste branch — it mutates the shared per-level radiation
 *       data. Wrapping this one call rather than all of {@code burnFuel} is
 *       what keeps the fuel burn arithmetic on the worker.</li>
 *   <li>{@code handleDamage} — reads {@code Level.getRandom()}, accumulates
 *       reactor damage and, on the meltdown roll, calls {@code createMeltdown},
 *       which writes the level's meltdown data and destroys blocks. The whole
 *       method moves; its damage arithmetic is a few comparisons.</li>
 *   <li>{@code radiateEntities} — a 1-in-20 AABB entity scan followed by a
 *       {@code radiate} per entity.</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "mekanism.generators.common.content.fission.FissionReactorMultiblockData", remap = false)
public abstract class FissionReactorMultiblockDataEjectMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V",
                    ordinal = 0),
            remap = false)
    private void mek$deferCoolantEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V",
                    ordinal = 1),
            remap = false)
    private void mek$deferWasteEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "burnFuel(Lnet/minecraft/world/level/Level;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/radiation/IRadiationManager;radiate"
                            + "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;D)V"),
            remap = false)
    private void mek$deferRadiateWaste(@Coerce Object manager, @Coerce Object world, @Coerce Object pos, double amount,
                                       Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, manager, world, pos, amount);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/generators/common/content/fission/FissionReactorMultiblockData;handleDamage"
                            + "(Lnet/minecraft/world/level/Level;)V"),
            remap = false)
    private void mek$deferHandleDamage(@Coerce Object self, @Coerce Object world, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, self, world);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/generators/common/content/fission/FissionReactorMultiblockData;radiateEntities"
                            + "(Lnet/minecraft/world/level/Level;)V"),
            remap = false)
    private void mek$deferRadiateEntities(@Coerce Object self, @Coerce Object world, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, self, world);
    }
}
