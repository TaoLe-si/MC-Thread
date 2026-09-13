package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

/**
 * One furnace / blast furnace / smoker {@code serverTick}. Recipe output, cook
 * time and burn duration are captured on the server thread.
 */
public record FurnaceTickSnapshot(
        String dimension,
        int x,
        int y,
        int z,
        String blockId,
        ItemCopy ingredient,
        ItemCopy fuel,
        ItemCopy result,
        int litTime,
        int litDuration,
        int cookingProgress,
        int cookingTotalTime,
        int burnDuration,
        int cookTime,
        ItemCopy recipeResult,
        ItemCopy fuelRemainder,
        boolean wetSponge) {
}
