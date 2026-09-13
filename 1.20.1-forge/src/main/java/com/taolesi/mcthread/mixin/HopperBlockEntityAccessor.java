package com.taolesi.mcthread.mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityAccessor {

    @Accessor("cooldownTime")
    int mcthread$getCooldownTime();

    @Accessor("tickedGameTime")
    void mcthread$setTickedGameTime(long value);
}
