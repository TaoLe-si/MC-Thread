package com.taolesi.mcthread.experiment.menu;

public record MenuDecision(
        boolean apply,
        boolean opensMenu,
        MenuSlotCopy[] slots,
        MenuSlotCopy carried,
        MenuSlotCopy offhand,
        MenuSlotCopy drop) {

    public static MenuDecision pass() {
        return new MenuDecision(false, false, null, MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY);
    }

    public static MenuDecision open() {
        return new MenuDecision(true, true, null, MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY);
    }

    public static MenuDecision mutate(MenuSlotCopy[] slots, MenuSlotCopy carried, MenuSlotCopy offhand) {
        return new MenuDecision(true, false, slots,
                carried == null ? MenuSlotCopy.EMPTY : carried,
                offhand == null ? MenuSlotCopy.EMPTY : offhand,
                MenuSlotCopy.EMPTY);
    }

    public static MenuDecision mutateDrop(MenuSlotCopy[] slots, MenuSlotCopy carried, MenuSlotCopy offhand,
                                          MenuSlotCopy drop) {
        return new MenuDecision(true, false, slots,
                carried == null ? MenuSlotCopy.EMPTY : carried,
                offhand == null ? MenuSlotCopy.EMPTY : offhand,
                drop == null ? MenuSlotCopy.EMPTY : drop);
    }
}
