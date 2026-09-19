package com.taolesi.threadtearer.enderio;

import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The compute-thread shape EnderIO's neighbour-IO follows: when {@link
 * InteractionRelocator#isComputing()} is true (a worker is running the BE's
 * tick), the {@code IItemHandler} / {@code IFluidHandler} calls underneath
 * {@code MachineBlockEntity.distributeItems} / {@code distributeFluids} are
 * not thread-safe. This helper defers them back to the server thread so the
 * worker can return immediately and the writes batch up at the tick boundary
 * — the same pattern {@code TileEntityConfigurableMachineEjectMixin} uses for
 * Mekanism's ejector.
 */
public final class EnderDeferral {
    private EnderDeferral() {
    }

    /**
     * Run {@code body} on the server thread under the BE's per-tick lock when
     * called from a compute worker; otherwise run it inline. The lock matches
     * the one {@link InteractionRelocator#stealTick} takes on the same BE, so
     * the worker's own tick and the deferred neighbour-IO cannot overlap on
     * the same machine.
     */
    public static void runOrDefer(BlockEntity lock, Runnable body) {
        if (!InteractionRelocator.isComputing()) {
            body.run();
            return;
        }
        MCT.runtime().deferWorldWrite(
                () -> InteractionRelocator.runLockedBlockEntityTick(lock, body));
    }
}