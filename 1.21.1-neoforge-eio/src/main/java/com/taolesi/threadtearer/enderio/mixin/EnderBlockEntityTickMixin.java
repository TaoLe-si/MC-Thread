package com.taolesi.threadtearer.enderio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.enderio.EnderOffloadPolicy;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The single chokepoint for every EnderIO machine.
 *
 * <p>{@code EIOEntityBlock.getTicker} returns one ticker lambda whose server
 * branch is {@code EnderBlockEntity.tick(Level, BlockPos, BlockState,
 * EnderBlockEntity)}; that static dispatcher calls {@code blockEntity.serverTick()}.
 * Wrapping that one {@code invokevirtual} intercepts every EnderIO BE —
 * Alloy Smelter, SAG Mill, Slicer, Vat, Soul Binder, Vacuum Chest, Powered
 * Spawner, the Obelisks, every Capacitor Bank tier, the generators — instead
 * of needing a mixin per machine. The policy gates per EnderIO class, so
 * non-crafting machines and machines whose tick reads the world never move.
 *
 * <p>What runs off-thread once a machine is admitted: the recipe matching
 * ({@code recipe.craft(...)} / {@code recipe.matches(...)}), the energy
 * consumption, and the BE's own inventory mutations
 * ({@code outputAccess.insertItem(inventory, ...)}, {@code consumeInputs}).
 * The neighbour auto-push — {@code MachineBlockEntity.distributeResources}
 * → {@code TransferUtil.distributeItems/Fluids} → live {@code IItemHandler} /
 * {@code IFluidHandler} reads and writes — is split off by
 * {@link MachineBlockEntityDistributeResourcesMixin} and forwarded back to the
 * server thread, the same way mek's ejector split is split off from mek's
 * configurable-machine {@code onUpdateServer}.
 *
 * <p>The handler takes the receiver as its first parameter — the
 * {@code invokevirtual} lesson from the AE2/mek 0.3.9 hotfixes — and the
 * dispatch follows the {@code InteractionRelocator.stealTick} shape: the
 * BE's tick body runs on a worker under {@code runLockedBlockEntityTick}, so
 * the deferred neighbour-IO below cannot overlap on the same machine.
 */
@Pseudo
@Mixin(targets = "com.enderio.core.common.blockentity.EnderBlockEntity", remap = false)
public abstract class EnderBlockEntityTickMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lcom/enderio/core/common/blockentity/EnderBlockEntity;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/enderio/core/common/blockentity/EnderBlockEntity;serverTick()V"),
            remap = false)
    private static void threadtearer$dispatchServerTick(@Coerce Object self, Operation<Void> original) {
        // self is the EnderBlockEntity instance — the receiver of serverTick().
        if (!(self instanceof BlockEntity be) || !EnderOffloadPolicy.mayOffload(be)) {
            original.call(self);
            return;
        }
        // Dispatch to a compute worker under the BE's per-tick lock; the lock
        // matches the one EnderDeferral takes when the deferred neighbour IO
        // runs on the server thread, so worker tick and server-thread IO cannot
        // overlap on the same machine.
        if (InteractionRelocator.stealTick(be, () -> original.call(self))) {
            return;
        }
        original.call(self);
    }
}