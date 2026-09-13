package com.taolesi.mcthread.experiment.furnace;

/**
 * Value copy of one menu slot. Flags are captured on the server thread so the
 * interaction thread never asks the recipe manager or {@code Level}.
 */
public record SlotCopy(String id, int count, int maxStack, boolean smeltable, boolean fuel) {

    public static final SlotCopy EMPTY = new SlotCopy("minecraft:air", 0, 64, false, false);

    public boolean isEmpty() {
        return count <= 0 || id == null || id.isEmpty() || "minecraft:air".equals(id);
    }
}
