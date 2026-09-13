package com.taolesi.mcthread.experiment.dropper;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

public record DropperDecision(boolean apply, ItemCopy[] dropper, ItemCopy[] dest) {

    public static DropperDecision pass() {
        return new DropperDecision(false, null, null);
    }

    public static DropperDecision move(ItemCopy[] dropper, ItemCopy[] dest) {
        return new DropperDecision(true, dropper, dest);
    }
}
