package com.taolesi.threadtearer.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the caller-name classification used by the name-based tick path.
 *
 * <p>An in-game run reported {@code computeTicks=0} with the Mekanism addon
 * installed and its mixins confirmed applied — nothing was ever offloaded,
 * because {@code stealTick(blockEntity, …)} gated on a caller-name guess that
 * a mixin handler can never satisfy. That entry point no longer consults the
 * name at all; these cases pin what the name-based path must still get right,
 * including the reverse bug (world calls being treated as ticks).
 */
class TickClassificationTest {

    @Test
    void vanillaBlockEntityTickNamesPass() {
        assertTrue(InteractionRelocator.isBlockEntityTick("BoundTickingBlockEntity.tick"));
        assertTrue(InteractionRelocator.isBlockEntityTick("LevelChunk$BoundTickingBlockEntity.tick"));
        assertTrue(InteractionRelocator.isBlockEntityTick("HopperBlockEntity.pushItemsTick"));
    }

    /**
     * Names that merely CONTAIN "blockentity" but belong to the Level world API
     * are not ticks. Offloading them is what produced the runaway
     * {@code computeTicks} (~98/tick) during chunk loading.
     */
    @Test
    void levelWorldCallsThatMentionBlockEntityAreNotTicks() {
        assertFalse(InteractionRelocator.isBlockEntityTick("Level.blockEntityChanged"));
        assertFalse(InteractionRelocator.isBlockEntityTick("Level.setBlockEntity"));
        assertFalse(InteractionRelocator.isBlockEntityTick("Level.removeBlockEntity"));
        assertFalse(InteractionRelocator.isBlockEntityTick("Level.addFreshBlockEntities"));
    }

    /**
     * A mixin handler frame reports the TARGET class and mixin's method prefix,
     * e.g. {@code TileEntityElectricMachine.handler$zdh000$mek$relocateOnUpdateServer}.
     * It satisfies no vanilla rule — which is exactly why the addon entry point
     * must not consult the name. Documented here so a future "let's gate it"
     * change fails loudly instead of silently disabling all offloading.
     */
    @Test
    void mixinHandlerFramesAreNotNameClassifiable() {
        assertFalse(InteractionRelocator.isBlockEntityTick(
                "TileEntityElectricMachine.handler$zdh000$mek$relocateOnUpdateServer"));
    }
}
