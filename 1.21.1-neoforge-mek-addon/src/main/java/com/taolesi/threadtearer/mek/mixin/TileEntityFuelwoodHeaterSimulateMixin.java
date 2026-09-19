package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import mekanism.api.heat.HeatAPI.HeatTransfer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Fuelwood variant of the resistive heater's heat-exchange deferral. The
 * tick body is even thinner ({@code fuelSlot.burn}, a single
 * {@code handleHeat} on the own capacitor, {@code setActive}, then
 * {@code simulate()}), but the neighbour-reach surface is identical, so the
 * mechanism is the same: whole {@code simulate()} runs under the tile lock on
 * the server thread. The worker side is pure fuel bookkeeping.
 *
 * <p>See {@link TileEntityResistiveHeaterSimulateMixin} for the full rationale
 * on why the defer is needed (the {@code BasicHeatCapacitor} write race, the
 * {@code BlockCapabilityCache} registration mutation) and on the
 * display-field one-tick lag the worker-side zero return produces. The two
 * mixins are otherwise identical; a shared helper would add an indirection the
 * addon doesn't need.
 *
 * <p>The {@code @At} target names the heater class, not {@code ITileHeatHandler} —
 * see the sibling mixin's javadoc for the bytecode-owner gotcha that made this
 * the difference between injection and a 0.3.9 startup crash.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.machine.TileEntityFuelwoodHeater", remap = false)
public abstract class TileEntityFuelwoodHeaterSimulateMixin {

    @Shadow
    private double lastEnvironmentLoss;

    @Shadow
    private double lastTransferLoss;

    @WrapOperation(
            method = "onUpdateServer()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/machine/TileEntityFuelwoodHeater;"
                            + "simulate()Lmekanism/api/heat/HeatAPI$HeatTransfer;"),
            remap = false)
    private HeatTransfer mek$deferSimulate(@Coerce Object self, Operation<HeatTransfer> original) {
        if (!InteractionRelocator.isComputing()) {
            HeatTransfer r = original.call(self);
            this.lastEnvironmentLoss = r.environmentTransfer();
            this.lastTransferLoss = r.adjacentTransfer();
            return r;
        }
        MCT.runtime().deferWorldWrite(() -> InteractionRelocator.runLockedBlockEntityTick((BlockEntity) self, () -> {
            HeatTransfer r = original.call(self);
            this.lastEnvironmentLoss = r.environmentTransfer();
            this.lastTransferLoss = r.adjacentTransfer();
        }));
        return new HeatTransfer(0.0, 0.0);
    }
}