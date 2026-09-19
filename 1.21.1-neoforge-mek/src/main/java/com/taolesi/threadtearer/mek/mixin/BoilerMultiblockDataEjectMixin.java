package com.taolesi.threadtearer.mek.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.taolesi.threadtearer.mek.MekDeferral;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Boiler multiblock: hand the three world-reaching calls in
 * {@code BoilerMultiblockData.tick} to the server thread and leave the steam /
 * coolant / heat arithmetic on the worker.
 *
 * <p>The two {@code ChemicalUtil.emit} calls (steam tank, then cooled coolant
 * tank) push into the neighbour boiler valves. They share a descriptor, so
 * {@code ordinal} 0 and 1 pin each one; {@code ordinal} counts the matching
 * INVOKEs in method order, which is the order they appear in the tick body.
 *
 * <p>The third is {@code hotMap.put} — a {@code public static final
 * Object2BooleanMap<UUID>} shared by every boiler on the server, backed by
 * {@code Object2BooleanOpenHashMap}, which is not thread-safe. Two boilers on
 * two workers writing it concurrently is the same class of bug as an off-thread
 * capability write, so it is deferred too.
 */
@Pseudo
@Mixin(targets = "mekanism.common.content.boiler.BoilerMultiblockData", remap = false)
public abstract class BoilerMultiblockDataEjectMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V",
                    ordinal = 0),
            remap = false)
    private void mek$deferSteamEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/util/ChemicalUtil;emit"
                            + "(Ljava/util/Collection;Lmekanism/api/chemical/IChemicalTank;)V",
                    ordinal = 1),
            remap = false)
    private void mek$deferCoolantEmit(@Coerce Object targets, @Coerce Object tank, Operation<Void> original) {
        MekDeferral.runOrDefer(this, original, targets, tank);
    }

    @WrapOperation(
            method = "tick(Lnet/minecraft/world/level/Level;)Z",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/Object2BooleanMap;put(Ljava/lang/Object;Z)Z"),
            remap = false)
    private boolean mek$deferHotMapPut(@Coerce Object map, @Coerce Object key, boolean value, Operation<Boolean> original) {
        return MekDeferral.runOrDeferBool(this, original, map, key, value);
    }
}
