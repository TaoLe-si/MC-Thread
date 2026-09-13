package com.taolesi.mcthread.experiment.menu;

/**
 * One menu slot. {@code placeMask} bit i means this slot accepts
 * {@link MenuSnapshot#types()}{@code [i]} (captured on the server thread).
 */
public record MenuSlotCopy(String id, int count, int maxStack, boolean mayTake, long placeMask) {

    public static final MenuSlotCopy EMPTY = new MenuSlotCopy("minecraft:air", 0, 64, true, 0L);

    public boolean isEmpty() {
        return count <= 0 || id == null || id.isEmpty() || "minecraft:air".equals(id);
    }

    public boolean mayPlaceType(int typeIndex) {
        if (typeIndex < 0 || typeIndex >= 63) {
            return false;
        }
        return (placeMask & (1L << typeIndex)) != 0;
    }
}
