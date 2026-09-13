package com.taolesi.mcthread.experiment.furnace;

/**
 * Interaction-thread result for a furnace BE request.
 *
 * @param slots 39-slot menu after the click, or {@code null} for OPEN/PASS
 */
public record FurnaceDecision(
        boolean apply,
        boolean opensMenu,
        SlotCopy[] slots,
        SlotCopy carried,
        SlotCopy drop) {

    public static FurnaceDecision pass() {
        return new FurnaceDecision(false, false, null, SlotCopy.EMPTY, SlotCopy.EMPTY);
    }

    public static FurnaceDecision open() {
        return new FurnaceDecision(true, true, null, SlotCopy.EMPTY, SlotCopy.EMPTY);
    }

    public static FurnaceDecision mutate(SlotCopy[] slots, SlotCopy carried) {
        return new FurnaceDecision(true, false, slots, carried == null ? SlotCopy.EMPTY : carried, SlotCopy.EMPTY);
    }

    public static FurnaceDecision mutateDrop(SlotCopy[] slots, SlotCopy carried, SlotCopy drop) {
        return new FurnaceDecision(true, false, slots, carried == null ? SlotCopy.EMPTY : carried,
                drop == null ? SlotCopy.EMPTY : drop);
    }
}
