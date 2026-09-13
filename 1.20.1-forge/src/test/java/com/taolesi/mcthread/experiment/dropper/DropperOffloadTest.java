package com.taolesi.mcthread.experiment.dropper;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropperOffloadTest {

    @Test
    void insertsOneIntoDest() {
        ItemCopy[] dropper = ItemCopy.empty(9);
        dropper[2] = new ItemCopy("minecraft:cobblestone", 4, 64);
        ItemCopy[] dest = ItemCopy.empty(27);
        boolean[] place = new boolean[27];
        java.util.Arrays.fill(place, true);
        DropperDecision decision = DropperRules.decide(new DropperSnapshot(
                "minecraft:overworld", 0, 0, 0, 1, 0, 0, 2, dropper, dest, place));
        assertTrue(decision.apply());
        assertEquals(3, decision.dropper()[2].count());
        assertEquals("minecraft:cobblestone", decision.dest()[0].id());
        assertEquals(1, decision.dest()[0].count());
    }

    @Test
    void rejectsWhenDestFull() {
        ItemCopy[] dropper = ItemCopy.empty(9);
        dropper[0] = new ItemCopy("minecraft:cobblestone", 1, 64);
        ItemCopy[] dest = ItemCopy.empty(1);
        dest[0] = new ItemCopy("minecraft:dirt", 64, 64);
        DropperDecision decision = DropperRules.decide(new DropperSnapshot(
                "minecraft:overworld", 0, 0, 0, 1, 0, 0, 0, dropper, dest, new boolean[]{true}));
        assertTrue(!decision.apply());
    }
}
