package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Induction matrix: hand the two world-reaching calls in
 * {@code MatrixMultiblockData.tick} to the server thread. The energy-container
 * tick and the two slot transfers before them are pure self-state.
 *
 * <p>{@code CableUtils.emit} pushes stored energy into the neighbour induction
 * ports. {@code markDirtyComparator} is the less obvious one: it compares the
 * new redstone level and, on a change, walks every valve through
 * {@code WorldUtils.getTileEntity} and calls {@code markDirtyComparator()} on
 * each — a neighbour lookup and a write on every valve of the structure.
 */
@Pseudo
@Mixin(targets = "mekanism.common.content.matrix.MatrixMultiblockData", remap = false)
public abstract class MatrixMultiblockDataEjectMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/CableUtils;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/energy/IEnergyContainer;J)V"),
            remap = false)
    private void mek$deferEnergyEmit(@Coerce Object targets, @Coerce Object container, long maxTransfer,
                                     Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, container, maxTransfer);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/content/matrix/MatrixMultiblockData;markDirtyComparator"
                            + "(Lnet/minecraft/world/level/Level;)V"),
            remap = false)
    private void mek$deferComparator(@Coerce Object self, @Coerce Object world, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, self, world);
    }
}
