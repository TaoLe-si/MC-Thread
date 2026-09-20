package com.taolesi.threadtearer.industrial.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.industrial.IfDeferral;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The world-interaction split for {@code HydroponicBedTile.work()} — the
 * machine the route-B rework exists for.
 *
 * <p>The bed's {@code work()} is a decision half (live reads choosing a
 * branch) plus three action calls whose bodies must run on the server
 * thread:
 * <ul>
 *   <li>{@code BonemealableBlock.performBonemeal(ServerLevel, RandomSource,
 *       BlockPos, BlockState)} — one call site; grows the crop above.
 *       Internally draws from the passed {@code RandomSource} ({@code work()}
 *       passes {@code level.random}, the level-wide shared one), fires
 *       particle/sound level events and writes blocks.</li>
 *   <li>{@code BlockState.randomTick(ServerLevel, BlockPos, RandomSource)}
 *       — three call sites (the 4x and 10x growth bursts); same class of
 *       body.</li>
 *   <li>{@code HydroponicBedTile.tryToHarvestAndReplant(...)} — two call
 *       sites; harvests through the third-party
 *       {@code PlantRecollectable} registry, drops and replants via
 *       {@code level.setBlockAndUpdate}, and mutates the bed's own output
 *       slots and ether buffer.</li>
 * </ul>
 *
 * <p>On a compute worker each call is deferred through
 * {@link IfDeferral#runOrDeferVoid}: {@code deferWorldWrite} queues it to
 * the WriteCoalescer, whose drain runs <b>on the server thread</b> at the
 * tick boundary. That is what makes every server-thread-only API inside
 * those bodies safe — the shared {@code level.random} draws, the particle
 * events, the third-party registry — they all execute on the server
 * thread, in FIFO order, under the bed's per-BE lock (so a deferred
 * harvest cannot overlap the same bed's next worker tick).
 *
 * <p><b>Re-read guard.</b> The worker chose its branch from a live read;
 * by drain time the block above the bed may have changed (a player broke
 * the crop). Vanilla {@code performBonemeal} implementations
 * {@code setValue} on the state they are given and throw on air, so each
 * deferred body re-reads {@code level.getBlockState(pos)} and only calls
 * through when the block is still the one the worker saw. The guard
 * compares block identity, which is what the vanilla implementations
 * branch on.
 *
 * <p><b>Return value.</b> {@code tryToHarvestAndReplant} returns a boolean
 * the caller branches on ({@code if (!ret) growth-burst}). The deferred
 * result cannot cross back to the worker, so the wrapper returns
 * {@code true} — optimistic "harvested". The failure fallback (one growth
 * burst) is skipped on a stale verdict and the next tick re-evaluates
 * from fresh reads; doing less on a stale verdict beats doing the wrong
 * action.
 *
 * <p>What stays on the worker: the branch reads
 * ({@code level.getBlockState}/{@code isEmptyBlock}, live reads the core
 * allows), the ether/water tank bookkeeping, and the {@code WorkAction}
 * construction. The handlers are instance methods — {@code this} is the
 * bed, so the deferral lock is the bed itself, exactly the shape
 * {@code ActiveTileFacingWorkMixin} uses. The harvest handler's
 * Titanium/IF-typed parameters are passed through as {@code @Coerce
 * Object} so this mixin keeps no compile-time dependency beyond vanilla;
 * they are opaque to the wrapper, which only forwards them.
 */
@Pseudo
@Mixin(targets = "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile", remap = false)
public abstract class HydroponicBedDeferralMixin {

    @WrapOperation(
            method = "work()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BonemealableBlock;"
                            + "performBonemeal(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)V"),
            remap = false)
    private void threadtearer$deferBonemeal(BonemealableBlock growable, ServerLevel level,
                                            RandomSource random, BlockPos pos, BlockState state,
                                            Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(growable, level, random, pos, state);
            return;
        }
        IfDeferral.runOrDeferVoid(this, () -> {
            if (level.getBlockState(pos).getBlock() == state.getBlock()) {
                original.call(growable, level, random, pos, state);
            }
        });
    }

    @WrapOperation(
            method = "work()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "randomTick(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"),
            remap = false)
    private void threadtearer$deferRandomTick(BlockState state, ServerLevel level, BlockPos pos,
                                              RandomSource random, Operation<Void> original) {
        if (!InteractionRelocator.isComputing()) {
            original.call(state, level, pos, random);
            return;
        }
        IfDeferral.runOrDeferVoid(this, () -> {
            if (level.getBlockState(pos).getBlock() == state.getBlock()) {
                original.call(state, level, pos, random);
            }
        });
    }

    @WrapOperation(
            method = "work()Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile$WorkAction;",
            at = @At(value = "INVOKE",
                    target = "Lcom/buuz135/industrial/block/agriculturehusbandry/tile/HydroponicBedTile;"
                            + "tryToHarvestAndReplant(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/neoforged/neoforge/items/IItemHandler;"
                            + "Lcom/hrznstudio/titanium/component/progress/ProgressBarComponent;"
                            + "Lcom/buuz135/industrial/block/tile/IndustrialWorkingTile;"
                            + "Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)Z"),
            remap = false)
    private boolean threadtearer$deferHarvest(@Coerce Object level, @Coerce Object pos, @Coerce Object state,
                                              @Coerce Object output, @Coerce Object etherBuffer,
                                              @Coerce Object tile, @Coerce Object supplier,
                                              @Coerce Object simulationOutput, Operation<Boolean> original) {
        if (!InteractionRelocator.isComputing()) {
            return original.call(level, pos, state, output, etherBuffer, tile, supplier, simulationOutput);
        }
        IfDeferral.runOrDeferVoid(this,
                () -> original.call(level, pos, state, output, etherBuffer, tile, supplier, simulationOutput));
        return true;
    }
}