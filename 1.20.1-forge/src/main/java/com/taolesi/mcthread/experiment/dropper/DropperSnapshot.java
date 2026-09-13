package com.taolesi.mcthread.experiment.dropper;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

public record DropperSnapshot(
        String dimension,
        int x,
        int y,
        int z,
        int destX,
        int destY,
        int destZ,
        int slot,
        ItemCopy[] dropper,
        ItemCopy[] dest,
        boolean[] destPlace) {
}
