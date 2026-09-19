package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Turbine multiblock (MekanismGenerators): hand the two world-reaching calls in
 * {@code TurbineMultiblockData.tick} to the server thread. The steam flow,
 * energy conversion, blade and condenser math — the bulk of the method — stays
 * on the worker.
 *
 * <p>The energy push is the simple case: {@code CableUtils.emit} returns
 * {@code void}, so it defers on its own.
 *
 * <p>The vent push is not. Vanilla is
 *
 * <pre>
 *   this.ventTank.extract(FluidUtils.emit(this.fluidOutputTargets, this.ventTank.getFluid()),
 *                         Action.EXECUTE, AutomationType.INTERNAL);
 * </pre>
 *
 * so the emit's return value is the amount the drain then removes from the vent
 * tank. Deferring only the emit would leave the drain running on the worker with
 * the zero the deferred emit returned: the neighbour received the water and the
 * tank kept it, duplicating it every tick. The two calls therefore move together
 * through {@link MekDeferral#runEmitThenDrain}. The tank is captured from the
 * {@code isEmpty()} guard that immediately precedes them, which is the only call
 * in this method whose receiver is the vent tank and whose return type
 * ({@code boolean}) a handler can declare without a compile-time Mekanism
 * dependency.
 */
@Pseudo
@Mixin(targets = "mekanism.generators.common.content.turbine.TurbineMultiblockData", remap = false)
public abstract class TurbineMultiblockDataEjectMixin {

    /** The vent tank, captured by the guard so the emit wrapper can drain it. */
    @Unique
    private Object mek$ventTank;

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/fluid/IExtendedFluidTank;isEmpty()Z"),
            remap = false)
    private boolean mek$captureVentTank(@Coerce Object tank, Operation<Boolean> original) {
        this.mek$ventTank = tank;
        return original.call(tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/FluidUtils;emit"
                            + "(Ljava/util/Collection;Lnet/neoforged/neoforge/fluids/FluidStack;)I"),
            remap = false)
    private int mek$deferFluidEmit(@Coerce Object targets, @Coerce Object fluid, Operation<Integer> original) {
        MekDeferral.runEmitThenDrain(this, original, new Object[]{targets, fluid}, this.mek$ventTank);
        return 0;
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/CableUtils;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;)V"),
            remap = false)
    private void mek$deferEnergyEmit(@Coerce Object targets, @Coerce Object container, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, container);
    }
}
