package com.taolesi.mcthread.experiment.dropper;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

/** Insert one item from the chosen dropper slot into the facing container. */
public final class DropperRules {

    private DropperRules() {
    }

    public static DropperDecision decide(DropperSnapshot snapshot) {
        if (snapshot == null || snapshot.dropper() == null || snapshot.dest() == null) {
            return DropperDecision.pass();
        }
        int slot = snapshot.slot();
        if (slot < 0 || slot >= snapshot.dropper().length) {
            return DropperDecision.pass();
        }
        ItemCopy src = snapshot.dropper()[slot];
        if (src == null || src.isEmpty()) {
            return DropperDecision.pass();
        }
        ItemCopy[] dropper = snapshot.dropper().clone();
        ItemCopy[] dest = snapshot.dest().clone();
        for (int d = 0; d < dest.length; d++) {
            if (snapshot.destPlace() == null || d >= snapshot.destPlace().length || !snapshot.destPlace()[d]) {
                continue;
            }
            ItemCopy at = dest[d] == null ? ItemCopy.EMPTY : dest[d];
            if (at.isEmpty()) {
                dest[d] = new ItemCopy(src.id(), 1, src.maxStack());
                dropper[slot] = shrink(src);
                return DropperDecision.move(dropper, dest);
            }
            if (at.id().equals(src.id()) && at.count() < at.maxStack()) {
                dest[d] = new ItemCopy(at.id(), at.count() + 1, at.maxStack());
                dropper[slot] = shrink(src);
                return DropperDecision.move(dropper, dest);
            }
        }
        return DropperDecision.pass();
    }

    private static ItemCopy shrink(ItemCopy src) {
        return src.count() <= 1 ? ItemCopy.EMPTY : new ItemCopy(src.id(), src.count() - 1, src.maxStack());
    }
}
