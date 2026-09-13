package com.taolesi.mcthread.experiment.menu;

import java.util.UUID;

/**
 * Frozen player-container interaction: {@code OPEN} or a click type name.
 *
 * <p>{@code playerInvStart} is the first slot whose container is the player
 * inventory (chest-like menus). {@code types} are the unique item ids used by
 * {@link MenuSlotCopy#placeMask()}.
 */
public record MenuSnapshot(
        String kind,
        int slot,
        int button,
        String dimension,
        int x,
        int y,
        int z,
        String blockId,
        int containerId,
        int playerInvStart,
        String[] types,
        MenuSlotCopy[] slots,
        MenuSlotCopy carried,
        MenuSlotCopy offhand,
        boolean creative,
        String hand,
        String hitFace,
        UUID playerId) {

    public boolean isOpen() {
        return "OPEN".equals(kind);
    }

    public static MenuSlotCopy[] emptySlots(int size) {
        MenuSlotCopy[] slots = new MenuSlotCopy[size];
        java.util.Arrays.fill(slots, MenuSlotCopy.EMPTY);
        return slots;
    }

    public int typeIndex(String id) {
        if (id == null || types == null) {
            return -1;
        }
        for (int i = 0; i < types.length; i++) {
            if (id.equals(types[i])) {
                return i;
            }
        }
        return -1;
    }
}
