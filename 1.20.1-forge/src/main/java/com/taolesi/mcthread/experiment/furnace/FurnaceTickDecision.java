package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

public record FurnaceTickDecision(
        boolean apply,
        ItemCopy ingredient,
        ItemCopy fuel,
        ItemCopy result,
        int litTime,
        int litDuration,
        int cookingProgress,
        int cookingTotalTime,
        boolean litChanged) {

    public static FurnaceTickDecision pass() {
        return new FurnaceTickDecision(false, ItemCopy.EMPTY, ItemCopy.EMPTY, ItemCopy.EMPTY, 0, 0, 0, 0, false);
    }
}
