package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Splits an offloaded machine tick so its neighbour push stays on the server
 * thread.
 *
 * <p>{@code TileEntityConfigurableMachine.onUpdateServer} is two things in one
 * method:
 *
 * <pre>
 *   boolean sendUpdatePacket = super.onUpdateServer();   // recipe / energy / slots
 *   this.ejectorComponent.tickServer();                  // push into a neighbour
 * </pre>
 *
 * <p>The first half is the expensive part and touches only the machine's own
 * state, so it belongs on a compute worker. The second half builds
 * {@code BlockCapabilityCache} objects over a {@code ServerLevel} and calls into
 * the neighbour's capability — when the neighbour is an ME-network block that
 * reaches AE2 from a worker thread and corrupts its per-level
 * {@code TickHandler} queue (62k logged NPEs and a minute-long stall, observed
 * in game). This hook wraps the one call site of that method in the whole of
 * Mekanism, so it splits every configurable machine — electric machines,
 * factories, chemical machines, energy cubes, chemical tanks — without a mixin
 * per subclass.
 *
 * <p>Before this split {@code MekOffloadPolicy} simply refused to offload any
 * machine whose ejector had an output side configured. In a real base that is
 * most machines, because a machine has to push its products somewhere, so the
 * refusal left a handful of machines on the compute pool and the multi-core
 * benefit was about 0.1% of the tick budget. Deferring the push keeps the
 * safety property and offloads the population.
 *
 * <p>This hook is also what makes the policy's shape rule sound: an offloaded
 * class is only ever one that extends {@code TileEntityConfigurableMachine}, so
 * the one world-reaching call in its inherited tick is guaranteed to be wrapped
 * here. A family whose own tick body reaches into the world — the generators,
 * the lasers, the multiblocks — is excluded by that rule rather than by a name
 * someone has to remember to add.
 *
 * <p>The deferred push re-enters the same per-block-entity lock the ticker
 * holds ({@link InteractionRelocator#runLockedBlockEntityTick}), so it cannot
 * overlap the next tick's recipe work on the machine's own slots. It lags by up
 * to one tick, which is invisible: item ejection already runs on a 10-tick
 * delay and the push sends whatever is in the slots at the time it runs, not a
 * delta computed from the previous tick.
 *
 * <p>The push is handed to the server thread's tick-boundary write batch rather
 * than the interaction FIFO, so a base full of ejecting machines costs the
 * server thread ONE task per tick. If the core cannot accept the deferral the
 * push is skipped for this tick instead of running on the worker — a missed
 * eject is harmless, an off-thread capability write is not.
 *
 * <p>Because the deferral is one tick wide, the deferred body re-checks that the
 * machine is still there ({@code isRemoved()} / {@code hasLevel()}) before
 * running. Vanilla never pushes out of a machine that was just broken, and the
 * extra reads are two plain field loads on the server thread.
 *
 * <p>The handler is an instance method because the tile is the <em>enclosing</em>
 * instance here: {@code tickServer()} is called on the ejector component, so the
 * wrapped call's receiver is the ejector, not the machine.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.prefab.TileEntityConfigurableMachine", remap = false)
public abstract class TileEntityConfigurableMachineEjectMixin {

    @WrapOperation(
            method = "onUpdateServer()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/component/TileComponentEjector;tickServer()V"),
            remap = false)
    private void mek$deferEject(@Coerce Object ejector, Operation<Void> original) {
        // Server thread: the normal case for a machine the policy refuses. Run
        // vanilla — no deferral is involved, so nothing can be lost.
        if (!InteractionRelocator.isComputing()) {
            original.call(ejector);
            return;
        }
        // On a worker the push must not run here. This is also the fail-closed
        // branch for a class the policy refuses: reaching it would mean a denied
        // tile got ticked off-thread anyway, and a missed push is harmless where
        // an off-thread capability write is not.
        BlockEntity be = (BlockEntity) (Object) this;
        MCT.runtime().deferWorldWrite(() -> {
            // The deferral opens a one-tick window in which the machine can be
            // broken or its chunk unloaded. Vanilla never runs this half on a
            // removed block entity, so do not either. Both reads are plain
            // fields and both are safe on the server thread, where this runs.
            if (be.isRemoved() || !be.hasLevel()) {
                return;
            }
            InteractionRelocator.runLockedBlockEntityTick(be, () -> original.call(ejector));
        });
    }
}
