package com.taolesi.mcthread.experiment.till;

import java.util.Map;

/**
 * Snapshot-only till rules matching vanilla 1.20.1 {@code HoeItem} tillables.
 *
 * <p>Kept as a pure lookup so the experiment can prove "decide off-thread"
 * without calling {@code Item.useOn} (which mutates live {@code Level}).
 */
public final class TillRules {

    public static final String DIRT = "minecraft:dirt";
    public static final String GRASS_BLOCK = "minecraft:grass_block";
    public static final String DIRT_PATH = "minecraft:dirt_path";
    public static final String COARSE_DIRT = "minecraft:coarse_dirt";
    public static final String FARMLAND = "minecraft:farmland";

    private static final Map<String, String> TILLABLES = Map.of(
            DIRT, FARMLAND,
            GRASS_BLOCK, FARMLAND,
            DIRT_PATH, FARMLAND,
            COARSE_DIRT, DIRT);

    private TillRules() {
    }

    public static boolean isTillable(String blockId) {
        return TILLABLES.containsKey(blockId);
    }

    public static TillDecision decide(TillSnapshot snapshot) {
        if (!snapshot.canHoeTill() || !snapshot.airAbove()) {
            return TillDecision.pass();
        }
        String result = TILLABLES.get(snapshot.blockId());
        return result == null ? TillDecision.pass() : TillDecision.till(result);
    }
}
