package com.taolesi.mcthread.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {

    @Accessor("litTime")
    int mcthread$getLitTime();

    @Accessor("litTime")
    void mcthread$setLitTime(int value);

    @Accessor("litDuration")
    int mcthread$getLitDuration();

    @Accessor("litDuration")
    void mcthread$setLitDuration(int value);

    @Accessor("cookingProgress")
    int mcthread$getCookingProgress();

    @Accessor("cookingProgress")
    void mcthread$setCookingProgress(int value);

    @Accessor("cookingTotalTime")
    int mcthread$getCookingTotalTime();

    @Accessor("cookingTotalTime")
    void mcthread$setCookingTotalTime(int value);
}
