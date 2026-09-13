package com.taolesi.mcthread.experiment.menu;

/**
 * Snapshot-only {@code AbstractContainerMenu.doClick} for chest-like menus
 * (container slots first, then player inventory). Furnace menus keep
 * {@code FurnaceRules}.
 *
 * <p>Slot place/take flags must already be on each {@link MenuSlotCopy}.
 */
public final class MenuRules {

    private MenuRules() {
    }

    public static MenuDecision decide(MenuSnapshot snapshot) {
        if (snapshot == null || snapshot.kind() == null) {
            return MenuDecision.pass();
        }
        if (snapshot.isOpen()) {
            return MenuDecision.open();
        }
        SlotMut[] slots = copy(snapshot);
        ItemMut carried = ItemMut.of(snapshot.carried(), snapshot);
        ItemMut offhand = ItemMut.of(snapshot.offhand(), snapshot);
        ItemMut drop = ItemMut.empty();
        boolean changed;
        switch (snapshot.kind()) {
            case "PICKUP" -> changed = pickup(slots, carried, drop, snapshot.slot(), snapshot.button());
            case "QUICK_MOVE" -> changed = quickMove(slots, snapshot.slot(), snapshot.playerInvStart());
            case "SWAP" -> changed = swap(slots, offhand, snapshot.slot(), snapshot.button(),
                    snapshot.playerInvStart());
            case "THROW" -> changed = throwItems(slots, carried, drop, snapshot.slot(), snapshot.button());
            case "PICKUP_ALL" -> changed = pickupAll(slots, carried, snapshot.slot(), snapshot.button());
            case "CLONE" -> changed = cloneSlot(slots, carried, snapshot.slot(), snapshot.creative());
            default -> {
                return MenuDecision.pass();
            }
        }
        if (!changed) {
            return MenuDecision.pass();
        }
        MenuSlotCopy frozenDrop = drop.freeze(0L, true);
        if (frozenDrop.isEmpty()) {
            return MenuDecision.mutate(freeze(slots), carried.freeze(0L, true), offhand.freeze(0L, true));
        }
        return MenuDecision.mutateDrop(freeze(slots), carried.freeze(0L, true), offhand.freeze(0L, true), frozenDrop);
    }

    private static boolean pickup(SlotMut[] slots, ItemMut carried, ItemMut drop, int slot, int button) {
        if (slot == -999) {
            if (carried.isEmpty()) {
                return false;
            }
            if (button == 1) {
                drop.takeFrom(carried, 1);
            } else {
                drop.takeAllFrom(carried);
            }
            return !drop.isEmpty();
        }
        if (slot < 0 || slot >= slots.length) {
            return false;
        }
        SlotMut dest = slots[slot];
        if (button == 1) {
            return rightClick(dest, carried);
        }
        if (carried.isEmpty()) {
            if (dest.item.isEmpty() || !dest.mayTake) {
                return false;
            }
            carried.takeAllFrom(dest.item);
            return true;
        }
        if (!dest.mayPlace(carried)) {
            if (dest.item.sameItem(carried) && dest.mayTake) {
                return carried.takeFrom(dest.item, dest.item.count) > 0;
            }
            return false;
        }
        if (dest.item.isEmpty() || dest.item.sameItem(carried)) {
            dest.item.mergeFrom(carried);
            return true;
        }
        if (!dest.mayTake) {
            return false;
        }
        dest.item.swapWith(carried);
        return true;
    }

    private static boolean rightClick(SlotMut dest, ItemMut carried) {
        if (carried.isEmpty()) {
            if (dest.item.isEmpty() || !dest.mayTake) {
                return false;
            }
            int half = (dest.item.count + 1) / 2;
            carried.takeFrom(dest.item, half);
            return true;
        }
        if (!dest.mayPlace(carried)) {
            return false;
        }
        if (dest.item.isEmpty() || dest.item.sameItem(carried)) {
            dest.item.takeFrom(carried, 1);
            return true;
        }
        return false;
    }

    private static boolean quickMove(SlotMut[] slots, int index, int playerInvStart) {
        if (index < 0 || index >= slots.length || slots[index].item.isEmpty() || !slots[index].mayTake) {
            return false;
        }
        if (playerInvStart < 0 || playerInvStart > slots.length) {
            playerInvStart = slots.length;
        }
        ItemMut moving = slots[index].item.copy();
        int before = moving.count;
        boolean moved;
        if (index < playerInvStart) {
            moved = moveItemStackTo(slots, moving, playerInvStart, slots.length, true);
        } else {
            moved = moveItemStackTo(slots, moving, 0, playerInvStart, false);
        }
        if (!moved || moving.count == before) {
            return false;
        }
        slots[index].item = moving;
        return true;
    }

    private static boolean swap(SlotMut[] slots, ItemMut offhand, int slot, int button, int playerInvStart) {
        if (slot < 0 || slot >= slots.length) {
            return false;
        }
        SlotMut dest = slots[slot];
        ItemMut other;
        if (button == 40) {
            other = offhand;
        } else if (button >= 0 && button <= 8) {
            int otherIndex = playerInvStart + 27 + button;
            if (otherIndex < 0 || otherIndex >= slots.length || otherIndex == slot) {
                return false;
            }
            other = slots[otherIndex].item;
        } else {
            return false;
        }
        if (other.isEmpty() && dest.item.isEmpty()) {
            return false;
        }
        if (other.isEmpty()) {
            if (!dest.mayTake) {
                return false;
            }
            other.takeAllFrom(dest.item);
            return true;
        }
        if (dest.item.isEmpty()) {
            if (!dest.mayPlace(other)) {
                return false;
            }
            dest.item.takeAllFrom(other);
            return true;
        }
        if (!dest.mayTake || !dest.mayPlace(other)) {
            return false;
        }
        dest.item.swapWith(other);
        return true;
    }

    private static boolean throwItems(SlotMut[] slots, ItemMut carried, ItemMut drop, int slot, int button) {
        if (!carried.isEmpty()) {
            return false;
        }
        if (slot < 0 || slot >= slots.length) {
            return false;
        }
        SlotMut source = slots[slot];
        if (source.item.isEmpty() || !source.mayTake) {
            return false;
        }
        int n = button == 1 ? source.item.count : 1;
        drop.takeFrom(source.item, n);
        return !drop.isEmpty();
    }

    private static boolean pickupAll(SlotMut[] slots, ItemMut carried, int slot, int button) {
        if (slot < 0 || slot >= slots.length || carried.isEmpty()) {
            return false;
        }
        SlotMut clicked = slots[slot];
        if (!clicked.item.isEmpty() && clicked.mayTake) {
            return false;
        }
        int start = button == 0 ? 0 : slots.length - 1;
        int step = button == 0 ? 1 : -1;
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = start; i >= 0 && i < slots.length && carried.count < carried.maxStack; i += step) {
                SlotMut s = slots[i];
                if (s.item.isEmpty() || !s.mayTake || !s.item.sameItem(carried)) {
                    continue;
                }
                if (pass == 0 && s.item.count == s.item.maxStack) {
                    continue;
                }
                int moved = carried.takeFrom(s.item, s.item.count);
                changed |= moved > 0;
            }
        }
        return changed;
    }

    private static boolean cloneSlot(SlotMut[] slots, ItemMut carried, int slot, boolean creative) {
        if (!creative || !carried.isEmpty() || slot < 0 || slot >= slots.length) {
            return false;
        }
        ItemMut src = slots[slot].item;
        if (src.isEmpty()) {
            return false;
        }
        carried.id = src.id;
        carried.maxStack = src.maxStack;
        carried.typeIndex = src.typeIndex;
        carried.count = src.maxStack;
        return true;
    }

    private static boolean moveItemStackTo(SlotMut[] slots, ItemMut moving, int start, int end, boolean reverse) {
        if (moving.isEmpty() || start >= end) {
            return false;
        }
        int original = moving.count;
        int i = reverse ? end - 1 : start;
        while (!moving.isEmpty() && (reverse ? i >= start : i < end)) {
            SlotMut slot = slots[i];
            if (!slot.item.isEmpty() && slot.item.sameItem(moving) && slot.mayPlace(moving)) {
                slot.item.mergeFrom(moving);
            }
            i += reverse ? -1 : 1;
        }
        i = reverse ? end - 1 : start;
        while (!moving.isEmpty() && (reverse ? i >= start : i < end)) {
            SlotMut slot = slots[i];
            if (slot.item.isEmpty() && slot.mayPlace(moving)) {
                int n = Math.min(moving.count, moving.maxStack);
                slot.item.become(moving, n);
                moving.count -= n;
                if (moving.count <= 0) {
                    moving.clear();
                }
            }
            i += reverse ? -1 : 1;
        }
        return moving.count != original;
    }

    private static SlotMut[] copy(MenuSnapshot snapshot) {
        MenuSlotCopy[] src = snapshot.slots();
        SlotMut[] out = new SlotMut[src == null ? 0 : src.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = SlotMut.of(src[i], snapshot);
        }
        return out;
    }

    private static MenuSlotCopy[] freeze(SlotMut[] slots) {
        MenuSlotCopy[] out = new MenuSlotCopy[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = slots[i].freeze();
        }
        return out;
    }

    static final class SlotMut {
        boolean mayTake;
        long placeMask;
        ItemMut item;

        static SlotMut of(MenuSlotCopy copy, MenuSnapshot snapshot) {
            SlotMut s = new SlotMut();
            if (copy == null) {
                s.mayTake = true;
                s.placeMask = 0L;
                s.item = ItemMut.empty();
                return s;
            }
            s.mayTake = copy.mayTake();
            s.placeMask = copy.placeMask();
            s.item = ItemMut.of(copy, snapshot);
            return s;
        }

        boolean mayPlace(ItemMut item) {
            if (item == null || item.isEmpty()) {
                return true;
            }
            return item.typeIndex >= 0 && (placeMask & (1L << item.typeIndex)) != 0;
        }

        MenuSlotCopy freeze() {
            return new MenuSlotCopy(
                    item.isEmpty() ? "minecraft:air" : item.id,
                    item.isEmpty() ? 0 : item.count,
                    Math.max(1, item.maxStack),
                    mayTake,
                    placeMask);
        }
    }

    static final class ItemMut {
        String id;
        int count;
        int maxStack;
        int typeIndex;

        static ItemMut empty() {
            ItemMut m = new ItemMut();
            m.clear();
            return m;
        }

        static ItemMut of(MenuSlotCopy copy, MenuSnapshot snapshot) {
            ItemMut m = new ItemMut();
            if (copy == null || copy.isEmpty()) {
                m.clear();
                return m;
            }
            m.id = copy.id();
            m.count = copy.count();
            m.maxStack = Math.max(1, copy.maxStack());
            m.typeIndex = snapshot.typeIndex(copy.id());
            return m;
        }

        ItemMut copy() {
            ItemMut m = new ItemMut();
            m.id = id;
            m.count = count;
            m.maxStack = maxStack;
            m.typeIndex = typeIndex;
            return m;
        }

        boolean isEmpty() {
            return count <= 0 || id == null || "minecraft:air".equals(id);
        }

        boolean sameItem(ItemMut other) {
            return !isEmpty() && !other.isEmpty() && id.equals(other.id);
        }

        void clear() {
            id = "minecraft:air";
            count = 0;
            maxStack = 64;
            typeIndex = -1;
        }

        void swapWith(ItemMut other) {
            String i = id;
            int c = count;
            int m = maxStack;
            int t = typeIndex;
            id = other.id;
            count = other.count;
            maxStack = other.maxStack;
            typeIndex = other.typeIndex;
            other.id = i;
            other.count = c;
            other.maxStack = m;
            other.typeIndex = t;
        }

        void mergeFrom(ItemMut from) {
            if (isEmpty()) {
                takeFrom(from, from.count);
                return;
            }
            if (!sameItem(from)) {
                return;
            }
            takeFrom(from, maxStack - count);
        }

        void takeAllFrom(ItemMut from) {
            takeFrom(from, from.count);
        }

        int takeFrom(ItemMut from, int n) {
            if (n <= 0 || from.isEmpty()) {
                return 0;
            }
            n = Math.min(n, from.count);
            if (isEmpty()) {
                id = from.id;
                maxStack = from.maxStack;
                typeIndex = from.typeIndex;
                count = 0;
            } else if (!sameItem(from)) {
                return 0;
            }
            n = Math.min(n, maxStack - count);
            if (n <= 0) {
                return 0;
            }
            count += n;
            from.count -= n;
            if (from.count <= 0) {
                from.clear();
            }
            return n;
        }

        void become(ItemMut from, int n) {
            id = from.id;
            maxStack = from.maxStack;
            typeIndex = from.typeIndex;
            count = n;
        }

        MenuSlotCopy freeze(long placeMask, boolean mayTake) {
            return isEmpty() ? MenuSlotCopy.EMPTY : new MenuSlotCopy(id, count, maxStack, mayTake, placeMask);
        }
    }
}
