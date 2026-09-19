package com.taolesi.threadtearer.industrial;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.api.MCT;
import com.taolesi.threadtearer.experiment.InteractionRelocator;

/**
 * The one place an offloaded Industrial Foregoing tick decides whether a
 * world-reaching call runs here or waits for the server thread.
 *
 * <p>Every IF machine built on Titanium ticks through
 * {@code ActiveTile.serverTick}, whose world reach is exactly one shape: the
 * auto-push of sided inventories and tanks into their neighbours
 * ({@code IFacingComponent.work}), which runs every
 * {@code getFacingHandlerWorkTime()} ticks. That push builds neighbour
 * capability lookups and writes into the neighbour's handler — the same
 * hazard class as the Mekanism ejector push this addon's sibling was built
 * around. All the calls routed here return {@code boolean} with the return
 * value discarded at the call site, so the worker-side stub can return
 * {@code false} without changing behaviour.
 *
 * <p><b>This class must not live in the {@code mixin} package.</b> Mixin owns
 * that package: any class in it is assumed to be a mixin declared in the
 * config, and a class that is <em>not</em> declared there may not be
 * referenced from a transformed target — the first such reference aborts the
 * game with {@code IllegalClassLoadError}. The Mekanism addon crashed exactly
 * this way at 0.3.7.
 *
 * <p>Off a compute worker the call is handed to the core's tick-boundary write
 * batch, which runs the whole batch as ONE server-thread task per tick. If
 * the core refuses the deferral the push is skipped for this tick rather than
 * run on the worker — a missed auto-push is harmless, an off-thread
 * capability write is not.
 */
public final class IfDeferral {

    private IfDeferral() {
    }

    /**
     * Run the wrapped {@code IFacingComponent.work} here, or defer it to the
     * server thread under the tile lock when this is a compute worker.
     *
     * <p>The lock is the block entity itself — the same object
     * {@code TitaniumTickerMixin} holds when it hands the whole tick to a
     * worker — so a deferred push can never overlap the next tick's progress
     * work on the same machine, and two machines still run in parallel.
     */
    public static boolean runOrDefer(Object lock, Operation<Boolean> op, Object... args) {
        if (!InteractionRelocator.isComputing()) {
            return op.call(args);
        }
        MCT.runtime().deferWorldWrite(() -> InteractionRelocator.runLockedBlockEntityTick(lock, () -> op.call(args)));
        return false;
    }
}