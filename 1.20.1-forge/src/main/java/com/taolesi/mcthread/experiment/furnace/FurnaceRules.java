package com.taolesi.mcthread.experiment.furnace;

/**
 * Snapshot-only furnace menu / open rules (vanilla 1.20.1 {@code AbstractFurnaceMenu}
 * clicks including PICKUP_ALL / CLONE, plus block {@code OPEN}).
 *
 * <p>Does not call {@code Level} or recipe APIs. Smeltable/fuel flags must already
 * be on each {@link SlotCopy}.
 */
public final class FurnaceRules {

    private FurnaceRules() {
    }

    public static FurnaceDecision decide(FurnaceSnapshot snapshot) {
        if (snapshot == null || snapshot.kind() == null) {
            return FurnaceDecision.pass();
        }
        if (snapshot.isOpen()) {
            return FurnaceDecision.open();
        }
        Mut[] slots = copy(snapshot.slots());
        Mut carried = Mut.of(snapshot.carried());
        Mut drop = Mut.empty();
        boolean changed;
        switch (snapshot.kind()) {
            case "PICKUP" -> changed = pickup(slots, carried, drop, snapshot.slot(), snapshot.button());
            case "QUICK_MOVE" -> changed = quickMove(slots, snapshot.slot());
            case "SWAP" -> changed = swapWithHotbar(slots, snapshot.slot(), snapshot.button());
            case "THROW" -> changed = throwItems(slots, carried, drop, snapshot.slot(), snapshot.button());
            case "PICKUP_ALL" -> changed = pickupAll(slots, carried, snapshot.slot(), snapshot.button());
            case "CLONE" -> changed = cloneSlot(slots, carried, snapshot.slot(), snapshot.creative());
            default -> {
                return FurnaceDecision.pass();
            }
        }
        if (!changed) {
            return FurnaceDecision.pass();
        }
        SlotCopy[] frozen = freeze(slots);
        SlotCopy frozenCarried = carried.freeze();
        SlotCopy frozenDrop = drop.freeze();
        if (frozenDrop.isEmpty()) {
            return FurnaceDecision.mutate(frozen, frozenCarried);
        }
        return FurnaceDecision.mutateDrop(frozen, frozenCarried, frozenDrop);
    }

    private static boolean pickup(Mut[] slots, Mut carried, Mut drop, int slot, int button) {
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
        Mut dest = slots[slot];
        if (button == 1) {
            return rightClick(dest, carried, slot);
        }
        if (carried.isEmpty()) {
            if (dest.isEmpty()) {
                return false;
            }
            carried.takeAllFrom(dest);
            return true;
        }
        if (!canInsert(slot, carried)) {
            return false;
        }
        if (dest.isEmpty() || dest.sameItem(carried)) {
            dest.mergeFrom(carried);
            return true;
        }
        dest.swapWith(carried);
        return true;
    }

    private static boolean rightClick(Mut dest, Mut carried, int slot) {
        if (carried.isEmpty()) {
            if (dest.isEmpty()) {
                return false;
            }
            int half = (dest.count + 1) / 2;
            carried.takeFrom(dest, half);
            return true;
        }
        if (!canInsert(slot, carried)) {
            return false;
        }
        if (dest.isEmpty() || dest.sameItem(carried)) {
            dest.takeFrom(carried, 1);
            return true;
        }
        return false;
    }

    private static boolean quickMove(Mut[] slots, int index) {
        if (index < 0 || index >= slots.length || slots[index].isEmpty()) {
            return false;
        }
        Mut moving = slots[index].copy();
        int before = moving.count;
        if (index == FurnaceSnapshot.RESULT) {
            if (!moveItemStackTo(slots, moving, FurnaceSnapshot.PLAYER_INV_START, FurnaceSnapshot.HOTBAR_END, true)) {
                return false;
            }
        } else if (index != FurnaceSnapshot.FUEL && index != FurnaceSnapshot.INGREDIENT) {
            if (moving.smeltable) {
                if (!moveItemStackTo(slots, moving, FurnaceSnapshot.INGREDIENT, FurnaceSnapshot.FUEL, false)) {
                    return false;
                }
            } else if (moving.fuel) {
                if (!moveItemStackTo(slots, moving, FurnaceSnapshot.FUEL, FurnaceSnapshot.RESULT, false)) {
                    return false;
                }
            } else if (index >= FurnaceSnapshot.PLAYER_INV_START && index < FurnaceSnapshot.PLAYER_INV_END) {
                if (!moveItemStackTo(slots, moving, FurnaceSnapshot.HOTBAR_START, FurnaceSnapshot.HOTBAR_END, false)) {
                    return false;
                }
            } else if (index >= FurnaceSnapshot.HOTBAR_START && index < FurnaceSnapshot.HOTBAR_END) {
                if (!moveItemStackTo(slots, moving, FurnaceSnapshot.PLAYER_INV_START, FurnaceSnapshot.PLAYER_INV_END, false)) {
                    return false;
                }
            }
        } else if (!moveItemStackTo(slots, moving, FurnaceSnapshot.PLAYER_INV_START, FurnaceSnapshot.HOTBAR_END, false)) {
            return false;
        }
        if (moving.count == before) {
            return false;
        }
        slots[index] = moving;
        return true;
    }

    private static boolean swapWithHotbar(Mut[] slots, int slot, int hotbar) {
        if (hotbar < 0 || hotbar > 8 || slot < 0 || slot >= slots.length) {
            return false;
        }
        int other = FurnaceSnapshot.HOTBAR_START + hotbar;
        if (other == slot) {
            return false;
        }
        Mut a = slots[slot];
        Mut b = slots[other];
        if (!canInsert(slot, b) || !canInsert(other, a)) {
            return false;
        }
        slots[slot] = b;
        slots[other] = a;
        return !a.sameState(b);
    }

    private static boolean throwItems(Mut[] slots, Mut carried, Mut drop, int slot, int button) {
        if (!carried.isEmpty()) {
            return false;
        }
        Mut source;
        if (slot == -999) {
            source = carried;
        } else if (slot >= 0 && slot < slots.length) {
            source = slots[slot];
        } else {
            return false;
        }
        if (source.isEmpty()) {
            return false;
        }
        int n = button == 1 ? source.count : 1;
        drop.takeFrom(source, n);
        return !drop.isEmpty();
    }

    private static boolean pickupAll(Mut[] slots, Mut carried, int slot, int button) {
        if (slot < 0 || slot >= slots.length || carried.isEmpty()) {
            return false;
        }
        Mut clicked = slots[slot];
        if (!clicked.isEmpty()) {
            return false;
        }
        int start = button == 0 ? 0 : slots.length - 1;
        int step = button == 0 ? 1 : -1;
        boolean changed = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = start; i >= 0 && i < slots.length && carried.count < carried.maxStack; i += step) {
                Mut s = slots[i];
                if (s.isEmpty() || !s.sameItem(carried)) {
                    continue;
                }
                if (pass == 0 && s.count == s.maxStack) {
                    continue;
                }
                int before = carried.count;
                carried.takeFrom(s, s.count);
                changed |= carried.count > before;
            }
        }
        return changed;
    }

    private static boolean cloneSlot(Mut[] slots, Mut carried, int slot, boolean creative) {
        if (!creative || !carried.isEmpty() || slot < 0 || slot >= slots.length) {
            return false;
        }
        Mut src = slots[slot];
        if (src.isEmpty()) {
            return false;
        }
        carried.id = src.id;
        carried.maxStack = src.maxStack;
        carried.smeltable = src.smeltable;
        carried.fuel = src.fuel;
        carried.count = src.maxStack;
        return true;
    }

    private static boolean moveItemStackTo(Mut[] slots, Mut moving, int start, int end, boolean reverse) {
        if (moving.isEmpty()) {
            return false;
        }
        int original = moving.count;
        int i = reverse ? end - 1 : start;
        while (!moving.isEmpty() && (reverse ? i >= start : i < end)) {
            Mut slot = slots[i];
            if (!slot.isEmpty() && slot.sameItem(moving) && canInsert(i, moving)) {
                slot.mergeFrom(moving);
            }
            i += reverse ? -1 : 1;
        }
        i = reverse ? end - 1 : start;
        while (!moving.isEmpty() && (reverse ? i >= start : i < end)) {
            Mut slot = slots[i];
            if (slot.isEmpty() && canInsert(i, moving)) {
                int n = Math.min(moving.count, moving.maxStack);
                slots[i].become(moving, n);
                moving.count -= n;
                if (moving.count <= 0) {
                    moving.clear();
                }
            }
            i += reverse ? -1 : 1;
        }
        return moving.count != original;
    }

    static boolean canInsert(int index, Mut item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        if (index == FurnaceSnapshot.INGREDIENT) {
            return item.smeltable;
        }
        if (index == FurnaceSnapshot.FUEL) {
            return item.fuel;
        }
        if (index == FurnaceSnapshot.RESULT) {
            return false;
        }
        return index >= FurnaceSnapshot.PLAYER_INV_START && index < FurnaceSnapshot.HOTBAR_END;
    }

    private static Mut[] copy(SlotCopy[] slots) {
        Mut[] out = new Mut[FurnaceSnapshot.MENU_SIZE];
        for (int i = 0; i < out.length; i++) {
            SlotCopy src = slots != null && i < slots.length && slots[i] != null ? slots[i] : SlotCopy.EMPTY;
            out[i] = Mut.of(src);
        }
        return out;
    }

    private static SlotCopy[] freeze(Mut[] slots) {
        SlotCopy[] out = new SlotCopy[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = slots[i].freeze();
        }
        return out;
    }

    static final class Mut {
        String id;
        int count;
        int maxStack;
        boolean smeltable;
        boolean fuel;

        static Mut empty() {
            return of(SlotCopy.EMPTY);
        }

        static Mut of(SlotCopy copy) {
            Mut m = new Mut();
            if (copy == null || copy.isEmpty()) {
                m.clear();
                return m;
            }
            m.id = copy.id();
            m.count = copy.count();
            m.maxStack = Math.max(1, copy.maxStack());
            m.smeltable = copy.smeltable();
            m.fuel = copy.fuel();
            return m;
        }

        Mut copy() {
            Mut m = new Mut();
            m.id = id;
            m.count = count;
            m.maxStack = maxStack;
            m.smeltable = smeltable;
            m.fuel = fuel;
            return m;
        }

        boolean isEmpty() {
            return count <= 0 || id == null || "minecraft:air".equals(id);
        }

        boolean sameItem(Mut other) {
            return !isEmpty() && !other.isEmpty() && id.equals(other.id);
        }

        boolean sameState(Mut other) {
            return isEmpty() && other.isEmpty()
                    || sameItem(other) && count == other.count;
        }

        void clear() {
            id = "minecraft:air";
            count = 0;
            maxStack = 64;
            smeltable = false;
            fuel = false;
        }

        void swapWith(Mut other) {
            String i = id;
            int c = count;
            int m = maxStack;
            boolean s = smeltable;
            boolean f = fuel;
            id = other.id;
            count = other.count;
            maxStack = other.maxStack;
            smeltable = other.smeltable;
            fuel = other.fuel;
            other.id = i;
            other.count = c;
            other.maxStack = m;
            other.smeltable = s;
            other.fuel = f;
        }

        void mergeFrom(Mut from) {
            if (isEmpty()) {
                takeFrom(from, from.count);
                return;
            }
            if (!sameItem(from)) {
                return;
            }
            int space = maxStack - count;
            takeFrom(from, space);
        }

        void takeAllFrom(Mut from) {
            takeFrom(from, from.count);
        }

        void takeFrom(Mut from, int n) {
            if (n <= 0 || from.isEmpty()) {
                return;
            }
            n = Math.min(n, from.count);
            if (isEmpty()) {
                id = from.id;
                maxStack = from.maxStack;
                smeltable = from.smeltable;
                fuel = from.fuel;
                count = 0;
            } else if (!sameItem(from)) {
                return;
            }
            n = Math.min(n, maxStack - count);
            if (n <= 0) {
                return;
            }
            count += n;
            from.count -= n;
            if (from.count <= 0) {
                from.clear();
            }
        }

        void become(Mut from, int n) {
            id = from.id;
            maxStack = from.maxStack;
            smeltable = from.smeltable;
            fuel = from.fuel;
            count = n;
        }

        SlotCopy freeze() {
            return isEmpty() ? SlotCopy.EMPTY : new SlotCopy(id, count, maxStack, smeltable, fuel);
        }
    }
}
