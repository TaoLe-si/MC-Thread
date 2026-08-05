package com.taolesi.mcthread.mixin;

import com.taolesi.mcthread.config.MCThreadConfig;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic capability lookup fast path (M3, {@code optimizations.capabilityCache}).
 *
 * <p>Caches the resolved {@link LazyOptional} per provider instance per
 * (capability, side). Only <em>present</em> results are cached; the cache is
 * cleared on {@code invalidateCaps()} / {@code reviveCaps()}, and a cached
 * {@link LazyOptional} self-invalidates if its backing provider invalidates.
 *
 * <p>Default <b>off</b>: must pass the A/B benchmark gate (>=5% improvement,
 * no behavior difference) in a real modpack before being enabled by default.
 */
// Forge's own classes are not present in the vanilla obfuscation mappings and their
// method descriptors are identical between dev and production, so no remap is needed.
@Mixin(value = CapabilityProvider.class, remap = false)
public abstract class CapabilityProviderMixin {

    private record CapKey(Capability<?> capability, Direction side) {
    }

    @Unique
    private final Map<CapKey, LazyOptional<?>> mcthread$capCache = new ConcurrentHashMap<>();

    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private <T> void mcthread$getCapabilityFastPath(Capability<T> capability, Direction side,
                                                    CallbackInfoReturnable<LazyOptional<T>> cir) {
        if (!MCThreadConfig.capabilityCache || capability == null) {
            return;
        }
        LazyOptional<?> cached = mcthread$capCache.get(new CapKey(capability, side));
        if (cached != null) {
            //noinspection unchecked
            cir.setReturnValue((LazyOptional<T>) cached);
        }
    }

    @Inject(method = "getCapability", at = @At("RETURN"))
    private <T> void mcthread$getCapabilityStore(Capability<T> capability, Direction side,
                                                 CallbackInfoReturnable<LazyOptional<T>> cir) {
        if (!MCThreadConfig.capabilityCache || capability == null) {
            return;
        }
        LazyOptional<T> result = cir.getReturnValue();
        if (result != null && result.isPresent()) {
            CapKey key = new CapKey(capability, side);
            if (mcthread$capCache.get(key) != result) {
                mcthread$capCache.put(key, result);
            }
        }
    }

    @Inject(method = {"invalidateCaps", "reviveCaps"}, at = @At("HEAD"))
    private void mcthread$clearCache(CallbackInfo ci) {
        mcthread$capCache.clear();
    }
}
