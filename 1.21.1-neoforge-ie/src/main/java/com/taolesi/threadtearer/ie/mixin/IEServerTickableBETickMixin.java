package com.taolesi.threadtearer.ie.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.taolesi.threadtearer.experiment.InteractionRelocator;
import com.taolesi.threadtearer.ie.IeOffloadPolicy;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The single chokepoint for every {@code IEServerTickableBE} in Immersive
 * Engineering — twenty-nine BEs by source count.
 *
 * <p>{@code IEServerTickableBE.makeTicker()} returns one ticker lambda
 * whose server branch is
 * {@code (level, pos, state, blockEntity) -> { IEServerTickableBE
 * tickable = ...; if (tickable.canTickAny()) tickable.tickServer(); }}; the
 * lambda body sits in the static method
 * {@code IEServerTickableBE.lambda$makeTicker$0} after compilation. Wrapping
 * the {@code invokevirtual tickServer()} invocation inside the lambda
 * intercepts every IEServerTickableBE tick — ChargingStation,
 * SampleDrill, Cloche, ConveyorBelt, WoodenBarrel, Windmill, Watermill,
 * FluidPump, Capacitor, ThermoelectricGen, the obelisks, the redstone
 * connectors — through one call site. The policy gates which subclass is
 * actually offloaded.
 *
 * <p>What runs off-thread once a machine is admitted: the entire
 * {@code tickServer()} body. For the three admitted machines that body
 * is pure own-state plus only the world calls the core's
 * {@code LevelMixin} already forwards — {@code level.updateNeighborsAt},
 * {@code level.updateNeighbourForOutputSignal}, {@code level.setBlockAndUpdate}.
 * For Cloche, the ejector (neighbour {@code IItemHandler.insertItem})
 * is wrapped separately by {@link ClocheEjectorMixin} and deferred to
 * the server thread via {@code deferWorldWrite + runLockedBlockEntityTick}.
 *
 * <p>The handler takes the receiver as its first parameter — the
 * {@code invokevirtual} lesson from the AE2 / mek 0.3.9 / IF / MKX
 * hotfixes. The receiver type is the {@code IEServerTickableBE} interface
 * declared in the source, and {@code @Coerce Object} avoids referencing
 * the interface from the mixin bytecode (so the class can be loaded
 * before Mixin loads the IE classpath).
 */
@Pseudo
@Mixin(targets = "blusunrize.immersiveengineering.common.blocks.ticking.IEServerTickableBE", remap = false)
public abstract class IEServerTickableBETickMixin {

    @WrapOperation(
            method = "lambda$makeTicker$0(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE",
                    target = "Lblusunrize/immersiveengineering/common/blocks/ticking/IEServerTickableBE;tickServer()V"),
            remap = false)
    private static void threadtearer$dispatchTickServer(@Coerce Object self, Operation<Void> original) {
        // self is the IEServerTickableBE instance — the receiver of tickServer().
        if (!(self instanceof BlockEntity be) || !IeOffloadPolicy.mayOffload(be)) {
            original.call(self);
            return;
        }
        // Dispatch to a compute worker under the BE's per-tick lock. The lock
        // matches the one ClocheEjectorMixin takes when the ejector runs on
        // the server thread, so worker tick and server-thread ejector cannot
        // overlap on the same machine.
        if (InteractionRelocator.stealTick(be, () -> original.call(self))) {
            return;
        }
        original.call(self);
    }
}