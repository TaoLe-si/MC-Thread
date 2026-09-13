package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceTickOffloadTest {

    @Test
    void ignitesAndConsumesFuel() {
        FurnaceTickSnapshot snap = tick(
                rawIron(1), coal(1), ItemCopy.EMPTY,
                0, 0, 0, 200, 1600, 200, ingot(1), ItemCopy.EMPTY, false);
        FurnaceTickDecision decision = FurnaceTickRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(1600, decision.litTime());
        assertTrue(decision.fuel().isEmpty());
        assertEquals(1, decision.cookingProgress());
        assertTrue(decision.litChanged());
    }

    @Test
    void finishesCookIntoResult() {
        FurnaceTickSnapshot snap = tick(
                rawIron(1), ItemCopy.EMPTY, ItemCopy.EMPTY,
                5, 1600, 199, 200, 0, 200, ingot(1), ItemCopy.EMPTY, false);
        FurnaceTickDecision decision = FurnaceTickRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(4, decision.litTime());
        assertTrue(decision.ingredient().isEmpty());
        assertEquals("minecraft:iron_ingot", decision.result().id());
        assertEquals(1, decision.result().count());
        assertEquals(0, decision.cookingProgress());
    }

    @Test
    void coolsProgressWhenUnlit() {
        FurnaceTickSnapshot snap = tick(
                ItemCopy.EMPTY, ItemCopy.EMPTY, ItemCopy.EMPTY,
                0, 0, 10, 200, 0, 200, ItemCopy.EMPTY, ItemCopy.EMPTY, false);
        FurnaceTickDecision decision = FurnaceTickRules.decide(snap);
        assertEquals(8, decision.cookingProgress());
    }

    private static FurnaceTickSnapshot tick(ItemCopy ingredient, ItemCopy fuel, ItemCopy result,
                                            int litTime, int litDuration, int progress, int total,
                                            int burnDuration, int cookTime, ItemCopy recipeResult,
                                            ItemCopy remainder, boolean wetSponge) {
        return new FurnaceTickSnapshot("minecraft:overworld", 0, 0, 0, "minecraft:furnace",
                ingredient, fuel, result, litTime, litDuration, progress, total,
                burnDuration, cookTime, recipeResult, remainder, wetSponge);
    }

    private static ItemCopy coal(int n) {
        return new ItemCopy("minecraft:coal", n, 64);
    }

    private static ItemCopy rawIron(int n) {
        return new ItemCopy("minecraft:raw_iron", n, 64);
    }

    private static ItemCopy ingot(int n) {
        return new ItemCopy("minecraft:iron_ingot", n, 64);
    }
}
