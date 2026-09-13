package com.taolesi.mcthread.experiment.hopper;

/**
 * Snapshot-only vanilla hopper transfer: one item out, else one item in.
 */
public final class HopperRules {

    private HopperRules() {
    }

    public static HopperDecision decide(HopperSnapshot snapshot) {
        if (snapshot == null || snapshot.hopper() == null) {
            return HopperDecision.pass();
        }
        Mut[] hopper = copy(snapshot.hopper());
        Mut[] dest = snapshot.hasDest() ? copy(snapshot.dest()) : null;
        Mut[] source = snapshot.hasSource() ? copy(snapshot.source()) : null;
        if (snapshot.fromTick()) {
            int cooldown = snapshot.cooldown() - 1;
            if (cooldown > 0) {
                return HopperDecision.tick(cooldown, freeze(hopper), dest == null ? null : freeze(dest),
                        source == null ? null : freeze(source));
            }
            if (dest != null && eject(hopper, dest, snapshot.destPlace())) {
                return HopperDecision.tick(8, freeze(hopper), freeze(dest), source == null ? null : freeze(source));
            }
            if (source != null && pull(hopper, source, snapshot.hopperPlace(), snapshot.sourceTake())) {
                return HopperDecision.tick(8, freeze(hopper), dest == null ? null : freeze(dest), freeze(source));
            }
            return HopperDecision.tick(0, freeze(hopper), dest == null ? null : freeze(dest),
                    source == null ? null : freeze(source));
        }
        if (dest != null && eject(hopper, dest, snapshot.destPlace())) {
            return HopperDecision.move(freeze(hopper), freeze(dest), source == null ? null : freeze(source));
        }
        if (source != null && pull(hopper, source, snapshot.hopperPlace(), snapshot.sourceTake())) {
            return HopperDecision.move(freeze(hopper), dest == null ? null : freeze(dest), freeze(source));
        }
        return HopperDecision.pass();
    }

    private static boolean eject(Mut[] hopper, Mut[] dest, boolean[] destPlace) {
        for (int h = 0; h < hopper.length; h++) {
            if (hopper[h].isEmpty()) {
                continue;
            }
            Mut one = hopper[h].copyOne();
            final int hopperIndex = h;
            if (insert(dest, one, d -> destPlace != null && indexOk(destPlace, d * hopper.length + hopperIndex)
                    && destPlace[d * hopper.length + hopperIndex])) {
                hopper[h].count--;
                if (hopper[h].count <= 0) {
                    hopper[h].clear();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean pull(Mut[] hopper, Mut[] source, boolean[] hopperPlace, boolean[] sourceTake) {
        for (int s = 0; s < source.length; s++) {
            if (source[s].isEmpty() || sourceTake == null || s >= sourceTake.length || !sourceTake[s]) {
                continue;
            }
            Mut one = source[s].copyOne();
            final int src = s;
            if (insert(hopper, one, h -> hopperPlace != null
                    && indexOk(hopperPlace, h * source.length + src)
                    && hopperPlace[h * source.length + src])) {
                source[s].count--;
                if (source[s].count <= 0) {
                    source[s].clear();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean insert(Mut[] dest, Mut moving, java.util.function.IntPredicate accepts) {
        if (dest == null || moving.isEmpty()) {
            return false;
        }
        for (int d = 0; d < dest.length; d++) {
            if (!accepts.test(d)) {
                continue;
            }
            Mut slot = dest[d];
            if (slot.isEmpty()) {
                slot.become(moving, 1);
                return true;
            }
            if (slot.sameItem(moving) && slot.count < slot.maxStack) {
                slot.count++;
                return true;
            }
        }
        return false;
    }

    private static boolean indexOk(boolean[] arr, int i) {
        return i >= 0 && i < arr.length;
    }

    private static Mut[] copy(ItemCopy[] src) {
        if (src == null) {
            return new Mut[0];
        }
        Mut[] out = new Mut[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = Mut.of(src[i]);
        }
        return out;
    }

    private static ItemCopy[] freeze(Mut[] slots) {
        ItemCopy[] out = new ItemCopy[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = slots[i].freeze();
        }
        return out;
    }

    static final class Mut {
        String id;
        int count;
        int maxStack;

        static Mut of(ItemCopy copy) {
            Mut m = new Mut();
            if (copy == null || copy.isEmpty()) {
                m.clear();
                return m;
            }
            m.id = copy.id();
            m.count = copy.count();
            m.maxStack = Math.max(1, copy.maxStack());
            return m;
        }

        boolean isEmpty() {
            return count <= 0 || id == null || "minecraft:air".equals(id);
        }

        boolean sameItem(Mut other) {
            return !isEmpty() && !other.isEmpty() && id.equals(other.id);
        }

        void clear() {
            id = "minecraft:air";
            count = 0;
            maxStack = 64;
        }

        Mut copyOne() {
            Mut m = new Mut();
            m.id = id;
            m.count = 1;
            m.maxStack = maxStack;
            return m;
        }

        void become(Mut from, int n) {
            id = from.id;
            maxStack = from.maxStack;
            count = n;
        }

        ItemCopy freeze() {
            return isEmpty() ? ItemCopy.EMPTY : new ItemCopy(id, count, maxStack);
        }
    }
}
