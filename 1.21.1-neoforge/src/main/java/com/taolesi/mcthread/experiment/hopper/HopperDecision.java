package com.taolesi.mcthread.experiment.hopper;

public record HopperDecision(
        boolean apply,
        int cooldown,
        boolean writeCooldown,
        ItemCopy[] hopper,
        ItemCopy[] dest,
        ItemCopy[] source) {

    public static HopperDecision pass() {
        return new HopperDecision(false, 0, false, null, null, null);
    }

    public static HopperDecision move(ItemCopy[] hopper, ItemCopy[] dest, ItemCopy[] source) {
        return new HopperDecision(true, 8, true, hopper, dest, source);
    }

    public static HopperDecision tick(int cooldown, ItemCopy[] hopper, ItemCopy[] dest, ItemCopy[] source) {
        return new HopperDecision(true, cooldown, true, hopper, dest, source);
    }
}
