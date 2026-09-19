package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.mek.MekOffloadPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Hands the master tile's heavy per-tick simulation to a compute worker and
 * leaves everything else in the tile's tick on the server thread.
 *
 * <p>{@code TileEntityMultiblock.onUpdateServer} is several things in one
 * method:
 *
 * <pre>
 *   this.structure.tick(this, ...)      // formation / validation, world reads
 *   ...
 *   multiblock.tick(this.level)         // the heavy per-family simulation
 *   this.getManager().markTicked(...)   // shared queue
 *   this.onUpdateServer(multiblock)     // subclass hook, neighbour updates
 * </pre>
 *
 * <p>Only the middle line moves. It is the entire CPU cost — the boiler's steam
 * and heat simulation, the turbine's flow and energy math, the fission burn and
 * coolant loop — and it is already guarded by {@code isMaster()}, so exactly one
 * block entity per structure runs it. The formation protocol, the shared
 * {@code MultiblockManager} queues, the comparator notifications and the
 * subclass hooks all stay where they are.
 *
 * <p>That split is why the multiblock family needs ONE hook here plus the
 * per-family emit defers, instead of offloading every casing. The casings share
 * the {@code MultiblockData} instance the master mutates; running them on
 * workers would be hundreds of concurrent readers of that object for no gain,
 * because their own tick bodies are empty. The policy still refuses them.
 *
 * <p>The lock is the {@code MultiblockData} instance, not the tile, so two
 * structures run in parallel while the blocks of one structure serialize against
 * the deferred pushes the family mixins schedule.
 *
 * <p>The wrapped call returns "should this tick broadcast an update packet".
 * That value cannot cross the async boundary, so an offloaded data tick returns
 * {@code false} — client sync for an offloaded structure may lag by one tick.
 *
 * <p>{@link MekOffloadPolicy#mayOffloadMultiblockData} gates the steal. Only the
 * families a per-family mixin defers qualify; anything else — the dynamic tank,
 * the evaporation plant, a data class from an addon — runs here, on the server
 * thread, exactly as before.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.prefab.TileEntityMultiblock", remap = false)
public abstract class TileEntityMultiblockDataTickMixin {

    @WrapOperation(
            method = "onUpdateServer()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/lib/multiblock/MultiblockData;tick"
                            + "(Lnet/minecraft/world/level/Level;)Z"),
            remap = false)
    private boolean mek$stealDataTick(@Coerce Object multiblock, @Coerce Object level, Operation<Boolean> original) {
        if (!MekOffloadPolicy.mayOffloadMultiblockData(multiblock)) {
            return original.call(multiblock, level);
        }
        if (InteractionRelocator.stealTick(multiblock, () -> original.call(multiblock, level))) {
            return false;
        }
        return original.call(multiblock, level);
    }
}
