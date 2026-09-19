package com.taolesi.threadtearer.ie.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.ie.IeOffloadPolicy;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The single chokepoint for every {@code IEServerTickableBE} in Immersive
 * Engineering — twenty-nine BEs by source count.
 *
 * <p>The first version of this mixin targeted the interface
 * {@code IEServerTickableBE} itself (wrapping the {@code tickServer()}
 * invocation inside its {@code lambda$makeTicker$0}). Mixin rejected it in
 * PREPARE: a class mixin cannot target an interface
 * ({@code InvalidMixinException: @Mixin target type mismatch ... is an
 * interface}). Interfaces can only be targeted by interface mixins, whose
 * member rules make a MixinExtras handler awkward.
 *
 * <p>This version targets the {@code invokestatic} of
 * {@code IEServerTickableBE.makeTicker()} inside
 * {@code IEEntityBlock$BEClassInspectedData.makeBaseTicker(Z)} — a record,
 * hence a class, hence a valid class-mixin target. The wrapped call
 * returns the fully-formed base ticker (the lambda that guards with
 * {@code canTickAny()} and calls {@code tickServer()}); this wrapper
 * returns a delegating ticker that applies the offload policy per BE per
 * tick before delegating. One call site, every server-ticking IE BE.
 *
 * <p>What runs off-thread once a machine is admitted: the entire base
 * ticker body — the {@code canTickAny()} guard (a cheap own-state read)
 * and the whole {@code tickServer()}. For the three admitted machines that
 * body is pure own-state plus only the world calls the core's
 * {@code LevelMixin} already forwards; for Cloche the one neighbour push
 * is wrapped separately by {@link ClocheEjectorMixin} and deferred to the
 * server thread.
 *
 * <p>The handler signature follows the invokestatic rule: no receiver
 * parameter, the wrapped call's zero arguments, then the
 * {@code Operation}. Its return type is the wrapped call's
 * {@code BlockEntityTicker} — a vanilla class, so no {@code @Coerce} is
 * needed and the addon keeps no compile-time IE dependency for this mixin.
 */
@Pseudo
@Mixin(targets = "blusunrize.immersiveengineering.common.blocks.IEEntityBlock$BEClassInspectedData", remap = false)
public abstract class IEClassInspectedDataTickerMixin {

    @WrapOperation(
            method = "makeBaseTicker(Z)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;",
            at = @At(value = "INVOKE",
                    target = "Lblusunrize/immersiveengineering/common/blocks/ticking/IEServerTickableBE;"
                            + "makeTicker()Lnet/minecraft/world/level/block/entity/BlockEntityTicker;"),
            remap = false)
    private static BlockEntityTicker<BlockEntity> threadtearer$wrapServerTicker(
            Operation<BlockEntityTicker<BlockEntity>> original) {
        BlockEntityTicker<BlockEntity> base = original.call();
        // The delegating ticker: policy gate per BE per tick, then either
        // dispatch the whole base tick (guard + tickServer) to a compute
        // worker under the BE's per-tick lock, or run it inline.
        return (Level level, BlockPos pos, BlockState state, BlockEntity be) -> {
            if (IeOffloadPolicy.mayOffload(be)
                    && InteractionRelocator.stealTick(be, () -> base.tick(level, pos, state, be))) {
                return;
            }
            base.tick(level, pos, state, be);
        };
    }
}