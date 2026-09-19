package com.taolesi.threadtearer.industrial.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.industrial.IndustrialOffloadPolicy;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The single chokepoint for every Titanium-based block entity — which is every
 * Industrial Foregoing machine.
 *
 * <p>{@code BasicTileBlock.getTicker} returns one lambda whose server branch
 * is {@code ((ITickableBlockEntity) blockEntity).serverTick(level, pos, state,
 * blockEntity)}. Wrapping that one {@code invokeinterface} intercepts the
 * whole of IF — machines, generators, area workers — instead of needing a
 * mixin per machine. This is the same shape as the Mekanism addon's
 * {@code TileEntityMekanismTickMixin}, with one difference worth naming: the
 * chokepoint lives in the Titanium framework, not in IF itself, so this mixin
 * targets Titanium. The policy still gates per IF class, so machines from
 * other Titanium mods are never offloaded.
 *
 * <p>What runs off-thread once a tile is admitted: the progress-bar update
 * (recipe work via {@code work()} / {@code onFinish()}), the energy
 * component, and the machine's own tanks and slots. The auto-push into
 * neighbours is split back to the server thread by
 * {@code ActiveTileFacingWorkMixin}. Everything else IF does per tick stays
 * where it always was.
 *
 * <p>The handler takes the receiver as its first parameter — the
 * {@code invokeinterface} lesson from the AE2/mek 0.3.9 hotfixes — and the
 * {@code @At} target names the interface, because an {@code invokeinterface}
 * keeps its symbolic reference owned by the interface (the opposite of the
 * {@code invokevirtual}-default-method gotcha). The receiver here is the
 * lambda's own {@code blockEntity} argument, so it arrives twice: once as the
 * receiver and once as the fourth call argument. MixinExtras wants both.
 */
@Pseudo
@Mixin(targets = "com.hrznstudio.titanium.block.BasicTileBlock", remap = false)
public abstract class TitaniumTickerMixin {

    @WrapOperation(
            method = "lambda$getTicker$5(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/hrznstudio/titanium/block/tile/ITickableBlockEntity;"
                            + "serverTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V"),
            remap = false)
    private static void industrial$offloadServerTick(@Coerce Object self, @Coerce Object level,
                                                     @Coerce Object pos, @Coerce Object state,
                                                     @Coerce Object entity, Operation<Void> original) {
        BlockEntity be = (BlockEntity) entity;
        if (!IndustrialOffloadPolicy.mayOffload(be)) {
            original.call(self, level, pos, state, entity);
            return;
        }
        if (InteractionRelocator.stealTick(be, () -> original.call(self, level, pos, state, entity))) {
            return;
        }
        original.call(self, level, pos, state, entity);
    }
}