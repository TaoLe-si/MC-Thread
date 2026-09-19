package com.taolesi.threadtearer.mekx.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.mekx.MkxOffloadPolicy;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The single chokepoint for every Mekanism Extras factory.
 *
 * <p>{@code TileEntityConfigurableMachine.getTicker} returns one ticker
 * lambda whose server branch is
 * {@code TileEntityMekanism.tick(Level, BlockPos, BlockState, TileEntityMekanism)};
 * that static dispatcher calls {@code blockEntity.onUpdateServer()}. Every
 * Mekanism Extras factory reaches that call through its own
 * {@code onUpdateServer} override, which calls
 * {@code super.onUpdateServer()} (the mek
 * {@code TileEntityConfigurableMachine.onUpdateServer}). Wrapping this
 * single {@code invokevirtual} intercepts every factory without needing a
 * mixin per machine.
 *
 * <p><b>Composition with the mek addon's mixin.</b> The mek addon already
 * wraps the same call site in {@code TileEntityMekanismTickMixin}. MixinExtras'
 * {@code @WrapOperation} supports nested wrappers: both mixins apply, and the
 * outermost calls the next via {@code Operation.call(self)}. Order is set
 * by classpath / mixin priority; either order is safe because the two
 * policies' allowlists are disjoint:
 * <ul>
 *   <li>MKX factory: MKX policy says yes, mek policy says no (chain contains
 *       {@code com.jerry.mekextras.*}, outside {@code mekanism.}). The
 *       outer wrapper dispatches to a worker via {@code stealTick}; the inner
 *       wrapper runs synchronously because the mek policy rejected. The
 *       work happens on the worker, with the ejector defer the mek mixin
 *       already owns.</li>
 *   <li>Mek configurable machine: MKX policy says no (leaf not in
 *       {@code ALLOWED}). The MKX wrapper falls through to
 *       {@code original.call(self)}; the mek wrapper dispatches to a worker.
 *       Identical to the case where MKX were not installed.</li>
 * </ul>
 *
 * <p><b>What this mixin does not do.</b> It writes no ejector mixin of its
 * own. The {@code TileComponentEjector.tickServer()} defer belongs to the
 * mek addon ({@code TileEntityConfigurableMachineEjectMixin}); both
 * addons depend on it being installed at the same time, which is why the
 * {@code neoforge.mods.toml} declares {@code threadtearer_mek} as a hard
 * prerequisite.
 *
 * <p>The handler takes the receiver as its first parameter — the
 * {@code invokevirtual} lesson from the AE2/mek 0.3.9 hotfixes.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.base.TileEntityMekanism", remap = false)
public abstract class TileEntityMekanismTickMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lmekanism/common/tile/base/TileEntityMekanism;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/base/TileEntityMekanism;onUpdateServer()Z"),
            remap = false)
    private static void threadtearer$dispatchOnUpdateServer(@Coerce Object self, Operation<Boolean> original) {
        // self is the TileEntityMekanism instance — the receiver of onUpdateServer().
        if (!(self instanceof BlockEntity be) || !MkxOffloadPolicy.mayOffload(be)) {
            original.call(self);
            return;
        }
        // Dispatch to a compute worker under the BE's per-tick lock; the lock
        // matches the one the mek addon's ejector defer takes when the
        // neighbour-IO runs on the server thread, so worker tick and
        // server-thread ejector cannot overlap on the same machine.
        if (InteractionRelocator.stealTick(be, () -> original.call(self))) {
            return;
        }
        original.call(self);
    }
}