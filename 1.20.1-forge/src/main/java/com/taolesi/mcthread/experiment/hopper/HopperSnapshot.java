package com.taolesi.mcthread.experiment.hopper;

/**
 * One hopper transfer, or a full {@code pushItemsTick} when {@code fromTick}.
 */
public record HopperSnapshot(
        String dimension,
        int x,
        int y,
        int z,
        int destX,
        int destY,
        int destZ,
        boolean hasDest,
        int srcX,
        int srcY,
        int srcZ,
        boolean hasSource,
        ItemCopy[] hopper,
        ItemCopy[] dest,
        ItemCopy[] source,
        boolean[] destPlace,
        boolean[] hopperPlace,
        boolean[] sourceTake,
        int cooldown,
        long gameTime,
        boolean fromTick) {

    public static HopperSnapshot transfer(
            String dimension, int x, int y, int z,
            int destX, int destY, int destZ, boolean hasDest,
            int srcX, int srcY, int srcZ, boolean hasSource,
            ItemCopy[] hopper, ItemCopy[] dest, ItemCopy[] source,
            boolean[] destPlace, boolean[] hopperPlace, boolean[] sourceTake) {
        return new HopperSnapshot(dimension, x, y, z, destX, destY, destZ, hasDest,
                srcX, srcY, srcZ, hasSource, hopper, dest, source, destPlace, hopperPlace, sourceTake,
                0, 0L, false);
    }
}
