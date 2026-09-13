package com.taolesi.mcthread.experiment.furnace;

import java.util.UUID;

/**
 * Frozen furnace BE interaction: either {@code OPEN} (player used the block)
 * or a menu click ({@code PICKUP}/{@code QUICK_MOVE}/{@code SWAP}/{@code THROW}).
 *
 * <p>{@code slots} is 39 copies (3 furnace + 27 inv + 9 hotbar) plus
 * {@code carried}. OPEN may leave player slots empty; validate then only
 * checks the block entity.
 */
public record FurnaceSnapshot(
        String kind,
        int slot,
        int button,
        String dimension,
        int x,
        int y,
        int z,
        String furnaceBlockId,
        int containerId,
        SlotCopy[] slots,
        SlotCopy carried,
        boolean creative,
        UUID playerId) {

    public static final int INGREDIENT = 0;
    public static final int FUEL = 1;
    public static final int RESULT = 2;
    public static final int MENU_SIZE = 39;
    public static final int PLAYER_INV_START = 3;
    public static final int PLAYER_INV_END = 30;
    public static final int HOTBAR_START = 30;
    public static final int HOTBAR_END = 39;

    public boolean isOpen() {
        return "OPEN".equals(kind);
    }

    public static SlotCopy[] emptySlots() {
        SlotCopy[] slots = new SlotCopy[MENU_SIZE];
        java.util.Arrays.fill(slots, SlotCopy.EMPTY);
        return slots;
    }
}
