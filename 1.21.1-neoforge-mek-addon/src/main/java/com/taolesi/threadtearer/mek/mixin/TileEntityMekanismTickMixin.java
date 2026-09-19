package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.mek.MekOffloadPolicy;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The single hook that covers every Mekanism block entity.
 *
 * <p>{@code TileEntityMekanism.tickServer} is the one static ticker vanilla
 * calls for all of them, and it invokes {@code onUpdateServer()} virtually.
 * Wrapping that one call site intercepts all 48 overrides — machines,
 * factories, tanks, cubes, QIO, multiblocks — instead of needing a mixin per
 * subclass. Everything else {@code tickServer} does (frequency component,
 * upgrade component, chunk loader, {@code setActive} blockstate, comparator,
 * update packet) stays on the server thread, which is exactly the split we
 * want: the recipe/energy work moves off-thread, the world bookkeeping does
 * not.
 *
 * <p>Bodies that must stay on the server thread are listed in
 * {@link MekOffloadPolicy}; those run through the untouched original.
 *
 * <p>The wrapped call returns "should this tick broadcast an update packet".
 * That value cannot cross the async boundary, so an offloaded tick returns
 * {@code false} — client sync for an offloaded machine may lag by one tick.
 *
 * <p>The addon has no compile-time Mekanism dependency (it is a {@code @Pseudo}
 * mixin), hence {@code @Coerce Object} for the instance parameter.
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.base.TileEntityMekanism", remap = false)
public abstract class TileEntityMekanismTickMixin {

    @WrapOperation(
            method = "tickServer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lmekanism/common/tile/base/TileEntityMekanism;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/base/TileEntityMekanism;onUpdateServer()Z"),
            remap = false)
    private static boolean mek$offloadOnUpdateServer(@Coerce Object self, Operation<Boolean> original) {
        BlockEntity be = (BlockEntity) self;
        if (!MekOffloadPolicy.mayOffload(be)) {
            return original.call(self);
        }
        if (InteractionRelocator.stealTick(be, () -> original.call(self))) {
            return false;
        }
        return original.call(self);
    }
}
