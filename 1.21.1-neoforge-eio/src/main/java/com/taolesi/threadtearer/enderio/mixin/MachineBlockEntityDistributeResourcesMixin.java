package com.taolesi.threadtearer.enderio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.enderio.EnderDeferral;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Splits the neighbour-IO half of an EnderIO machine tick back to the server
 * thread, the same shape Mekanism's ejector split uses.
 *
 * <p>{@code MachineBlockEntity.distributeResources(Direction)} is the entry
 * point that the no-arg {@code distributeResources()} (called from
 * {@code serverTick}) dispatches to for each side that is set to force. Its
 * body calls {@code distributeItems(side)} (every 20 ticks) and
 * {@code distributeFluids(side)} (every 5 ticks); both reach
 * {@code TransferUtil.distributeItems/Fluids} which calls
 * {@code from.extractItem/insertItem} on the source's and destination's live
 * {@code IItemHandler} / {@code IFluidHandler}. Those capability handlers are
 * not thread-safe — the same {@code IItemHandler.extractItem/insertItem} core
 * pipeline that mek/SFM/Pipez all defer.
 *
 * <p>On a compute worker (the chokepoint {@code EnderBlockEntityTickMixin}
 * dispatched the BE's tick), each {@code distributeItems/Fluids} call is
 * queued to the server thread under the BE's per-tick lock
 * ({@link EnderDeferral}). The worker returns immediately and the actual
 * item/fluid moves batch up at the tick boundary — the same FIFO shape
 * {@code TileComponentEjector.tickServer} uses in the mek addon. On the
 * server thread itself, this mixin is a no-op and the original call runs
 * inline.
 */
@Pseudo
@Mixin(targets = "com.enderio.enderio.foundation.block.entity.MachineBlockEntity", remap = false)
public abstract class MachineBlockEntityDistributeResourcesMixin {

    @WrapOperation(
            method = "distributeResources(Lnet/minecraft/core/Direction;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/enderio/enderio/foundation/block/entity/MachineBlockEntity;"
                            + "distributeItems(Lnet/minecraft/core/Direction;)V"),
            remap = false)
    private void threadtearer$deferDistributeItems(@Coerce Object self, Direction side, Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(self, side);
            return;
        }
        EnderDeferral.runOrDefer((BlockEntity) self, () -> original.call(self, side));
    }

    @WrapOperation(
            method = "distributeResources(Lnet/minecraft/core/Direction;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/enderio/enderio/foundation/block/entity/MachineBlockEntity;"
                            + "distributeFluids(Lnet/minecraft/core/Direction;)V"),
            remap = false)
    private void threadtearer$deferDistributeFluids(@Coerce Object self, Direction side, Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(self, side);
            return;
        }
        EnderDeferral.runOrDefer((BlockEntity) self, () -> original.call(self, side));
    }
}