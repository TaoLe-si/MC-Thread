package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * MekanismGenerators: hand the one world-reaching call in a generator's tick to
 * the server thread and leave the fuel and heat arithmetic on the worker.
 *
 * <p>{@code TileEntityGenerator.onUpdateServer} is the base every generator
 * shares, and it is short:
 *
 * <pre>
 *   boolean sendUpdatePacket = super.onUpdateServer();   // (nothing: base)
 *   if (canFunction()) {
 *       if (outputCaches == null) {                      // SERVER THREAD ONLY
 *           for (side : getEnergySides()) outputCaches.add(
 *               BlockEnergyCapabilityCache.create((ServerLevel) level, ...));
 *       }
 *       CableUtils.emit(outputCaches, energyContainer, getMaxOutput());   // deferred
 *   }
 *   return sendUpdatePacket;
 * </pre>
 *
 * <p>Both world-reaching things are in this method, and they need opposite
 * treatment. The cache build <em>cannot</em> be deferred — the very next
 * statement reads the list — and it registers a capability listener, which
 * mutates the level's non-thread-safe {@code byChunkThenBlock} map. So
 * {@code MekOffloadPolicy} keeps the whole tick on the server thread until the
 * list exists and lets it move from the tick after that. The emit is the
 * ordinary case: it pushes into the neighbour energy handlers, so it defers
 * like every other push in this addon.
 *
 * <p>The subclasses are the reason this is worth doing. Each of
 * {@code TileEntityBioGenerator} and {@code TileEntityGasGenerator} overrides
 * {@code onUpdateServer}, calls {@code super} (this method), and then spends the
 * rest of its body on its own tank, slot and energy container —
 * {@code BasicEnergyContainer.insert}, {@code BasicFluidTank.shrinkStack},
 * {@code FuelTank.setStack}. That is where a bank of generators burns its tick
 * budget, and all of it moves.
 *
 * <p>The other four generators in the family read the level from their own tick
 * body, and they are excluded by {@code MekOffloadPolicy} rather than by
 * anything here:
 * <ul>
 *   <li>{@code TileEntityHeatGenerator.getBoost} calls
 *       {@code WorldUtils.getFluidState} on all six neighbours and
 *       {@code level.dimensionType().ultraWarm()}.</li>
 *   <li>{@code TileEntitySolarGenerator.checkCanSeeSun} goes through
 *       {@code SolarCheck}, which re-runs {@code WorldUtils.canSeeSun} every 20
 *       ticks.</li>
 *   <li>{@code TileEntityWindGenerator.getMultiplier} reads
 *       {@code Level.getFluidState}, {@code canSeeSky} and
 *       {@code dimensionType()}.</li>
 * </ul>
 *
 * <p>All three return a value the tick body feeds straight into its arithmetic,
 * so unlike a push they cannot be deferred — deferring {@code getBoost} would
 * have to invent a number. Reading a chunk off-thread is the same race the rest
 * of this addon defers around, so those classes stay on the server thread and
 * only the emit-deferring members of the family move. {@code TileEntityAdvancedSolarGenerator}
 * inherits the refusal through {@code TileEntitySolarGenerator}.
 *
 * <p>The lock is the tile, the same object {@code TileEntityMekanismTickMixin}
 * locks on when it hands the tick to a worker, so a deferred push can never
 * overlap the next tick's arithmetic on the same generator.
 */
@Pseudo
@Mixin(targets = "mekanism.generators.common.tile.TileEntityGenerator", remap = false)
public abstract class TileEntityGeneratorEmitMixin {

    @WrapOperation(
            method = "onUpdateServer()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/CableUtils;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;J)V"),
            remap = false)
    private void mek$deferEnergyEmit(@Coerce Object caches, @Coerce Object container, long maxOutput,
                                     Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, caches, container, maxOutput);
    }
}
