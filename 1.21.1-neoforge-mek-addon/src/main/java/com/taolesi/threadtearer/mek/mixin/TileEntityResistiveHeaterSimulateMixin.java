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
 * Hands the resistive heater's heat exchange back to the server thread.
 *
 * <p>{@code TileEntityResistiveHeater.onUpdateServer} runs the energy
 * bookkeeping, then {@code HeatTransfer transfer = simulate()} which is the
 * whole point of the tile — iterate six sides, read each neighbour's heat
 * handler, write the exchanged heat into it. The reads build a
 * {@code BlockCapabilityCache} over the {@code ServerLevel} on first use per
 * side (which mutates the level's capability-listener map), and the writes go
 * straight into the neighbour's {@code BasicHeatCapacitor.heatToHandle} — a
 * plain {@code double} with no synchronization, so two adjacent heaters
 * offloaded at once would race and lose heat.
 *
 * <p>The fix is to run the whole exchange on the server thread under the tile
 * lock. On the server thread this mixin is a no-op: {@code simulate()} runs and
 * the result is written straight into the display fields. On a compute worker
 * the call is deferred via {@link MCT#deferWorldWrite} and the worker returns
 * a zero {@link HeatTransfer} so the energy bookkeeping immediately after the
 * call site continues. The deferred body re-runs {@code simulate()} under the
 * lock and overwrites the display fields before container sync at the tick
 * boundary. The work done off-thread is therefore just the energy container
 * extraction and the {@code setActive} flag — the heat network itself stays
 * serialized, exactly as it does today.
 *
 * <p>The {@code @At} target names the heater class, not {@code ITileHeatHandler}.
 * {@code invokevirtual} of an interface default method emits a symbolic
 * reference owned by the <em>implementing</em> class, not the interface — a
 * gotcha that costs the 0.3.9 crash: the Fuelwood mixin's first cut targeted
 * {@code ITileHeatHandler.simulate()LHeatTransfer;} and Mixin scanned 0 targets
 * because the constant pool entry is {@code Methodref TileEntityFuelwoodHeater.simulate}.
 *
 * <p>The handler also has to declare the receiver as its first parameter. For
 * an instance-method {@code @WrapOperation} MixinExtras expects the call site's
 * receiver at index 0 — omitting it produced the second 0.3.9 crash, with
 * MixinExtras' {@code InvalidInjectionException} naming the exact expected
 * signature. {@code @Coerce Object} keeps the mixin compile-clean without
 * naming the Mekanism class on the parameter.
 *
 * <p>The {@code Pseudo} mixin still uses string-based targeting so the mixin
 * can be compiled against a {@code compileOnly} Mekanism jar without coupling
 * the addon to Mekanism at runtime; the {@link HeatTransfer} return type is
 * the only reason the dep exists, and it erases to {@code Object} at runtime.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.machine.TileEntityResistiveHeater", remap = false)
public abstract class TileEntityResistiveHeaterSimulateMixin {

    @Shadow
    private double lastEnvironmentLoss;

    @Shadow
    private double lastTransferLoss;

    @WrapOperation(
            method = "onUpdateServer()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/machine/TileEntityResistiveHeater;"
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