package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.hopper.ItemCopy;

/**
 * Snapshot-only vanilla 1.20.1 {@code AbstractFurnaceBlockEntity.serverTick}.
 */
public final class FurnaceTickRules {

    private FurnaceTickRules() {
    }

    public static FurnaceTickDecision decide(FurnaceTickSnapshot snapshot) {
        if (snapshot == null) {
            return FurnaceTickDecision.pass();
        }
        Mut ingredient = Mut.of(snapshot.ingredient());
        Mut fuel = Mut.of(snapshot.fuel());
        Mut result = Mut.of(snapshot.result());
        Mut recipeResult = Mut.of(snapshot.recipeResult());
        Mut remainder = Mut.of(snapshot.fuelRemainder());
        int litTime = snapshot.litTime();
        int litDuration = snapshot.litDuration();
        int cookingProgress = snapshot.cookingProgress();
        int cookingTotalTime = snapshot.cookingTotalTime();
        boolean wasLit = litTime > 0;
        if (wasLit) {
            litTime--;
        }
        boolean hasInput = !ingredient.isEmpty();
        boolean hasFuel = !fuel.isEmpty();
        if (litTime > 0 || (hasFuel && hasInput)) {
            if (litTime <= 0 && canBurn(ingredient, result, recipeResult)) {
                litTime = Math.max(0, snapshot.burnDuration());
                litDuration = litTime;
                if (litTime > 0) {
                    consumeFuel(fuel, remainder);
                }
            }
            if (litTime > 0 && canBurn(ingredient, result, recipeResult)) {
                cookingProgress++;
                if (cookingProgress == cookingTotalTime) {
                    cookingProgress = 0;
                    cookingTotalTime = Math.max(1, snapshot.cookTime());
                    cook(ingredient, result, recipeResult, fuel, snapshot.wetSponge());
                }
            } else {
                cookingProgress = 0;
            }
        } else if (litTime <= 0 && cookingProgress > 0) {
            cookingProgress = Math.max(0, Math.min(cookingProgress - 2, Math.max(0, cookingTotalTime)));
        }
        boolean litChanged = wasLit != (litTime > 0);
        return new FurnaceTickDecision(
                true,
                ingredient.freeze(),
                fuel.freeze(),
                result.freeze(),
                litTime,
                litDuration,
                cookingProgress,
                cookingTotalTime,
                litChanged);
    }

    private static boolean canBurn(Mut ingredient, Mut result, Mut recipeResult) {
        if (ingredient.isEmpty() || recipeResult.isEmpty()) {
            return false;
        }
        if (result.isEmpty()) {
            return true;
        }
        if (!result.sameItem(recipeResult)) {
            return false;
        }
        int cap = Math.min(result.maxStack, 64);
        return result.count + recipeResult.count <= cap;
    }

    private static void consumeFuel(Mut fuel, Mut remainder) {
        if (!remainder.isEmpty()) {
            fuel.id = remainder.id;
            fuel.maxStack = remainder.maxStack;
            fuel.count = remainder.count;
            return;
        }
        fuel.count--;
        if (fuel.count <= 0) {
            fuel.clear();
        }
    }

    private static void cook(Mut ingredient, Mut result, Mut recipeResult, Mut fuel, boolean wetSponge) {
        if (result.isEmpty()) {
            result.become(recipeResult, recipeResult.count);
        } else {
            result.count += recipeResult.count;
        }
        if (wetSponge && "minecraft:bucket".equals(fuel.id)) {
            fuel.id = "minecraft:water_bucket";
            fuel.count = 1;
            fuel.maxStack = 1;
        }
        ingredient.count--;
        if (ingredient.count <= 0) {
            ingredient.clear();
        }
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
