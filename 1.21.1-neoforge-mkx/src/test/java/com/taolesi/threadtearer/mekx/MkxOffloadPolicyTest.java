package com.taolesi.threadtearer.mekx;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the offload allowlist for Mekanism Extras.
 *
 * <p>The mek addon's {@code MekOffloadPolicy} is the shape reference — every
 * Mekanism Extras factory sits below {@code TileEntityConfigurableMachine}
 * in the same parent chain the mek addon already trusts. The closed allowlist
 * here is narrower than the mek addon's: only the eleven mekaf factories and
 * the five mekmm factories, none of which read the world or touch a
 * neighbour capability outside the ejector defer the mek addon wraps.
 *
 * <p>The chain walk fixes the "name typo passes the leaf check" trap: a
 * missing letter in a leaf entry passes {@code ALLOWED.contains(...)} but the
 * chain fails the package walk as soon as it leaves the {@code mekextras.} /
 * {@code mekanism.} boundary, so a class added to the set must also walk.
 */
class MkxOffloadPolicyTest {

    /** The shared Mekanism base every admitted factory reaches. */
    private static final String MEK_BASE =
            "mekanism.common.tile.prefab.TileEntityConfigurableMachine";

    /** Intermediate bases inside the mekextras package. */
    private static final String MKAF_BASE =
            "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraAdvancedBase";
    private static final String MKMM_BASE =
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraMoreMachineFactory";

    /** The allowlist is exactly the eleven mekaf plus five mekmm factories. */
    @Test
    void allowListIsExactlyTheAuditedMachines() {
        assertEquals(Set.of(
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraCentrifugingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraChemicalInfusingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraChemicalToChemicalFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraChemicalToItemFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraCrystallizingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraDissolvingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraItemToChemicalFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraLiquifyingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraOxidizingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraPRCFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraWashingFactory",
                "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraItemStackToItemStackMoreMachineFactory",
                "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraItemToItemMoreMachineFactory",
                "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraPlantingFactory",
                "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraRecyclingFactory",
                "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraReplicatingFactory"),
                MkxOffloadPolicy.allowedClasses());
        assertEquals(16, MkxOffloadPolicy.allowedSize());
    }

    /**
     * The admitted machines, each through its real chain. Both abstract bases
     * sit between the concrete factory and {@code TileEntityConfigurableMachine}
     * (the mek addon's {@code DEFERRED_SHAPE}) — the chain the ejector defer
     * already covers, which is why this addon writes no ejector mixin of its
     * own.
     */
    @Test
    void admittedMachinesAreOffloadedThroughTheirRealChains() {
        for (String leaf : MkxOffloadPolicy.allowedClasses()) {
            if (leaf.startsWith("com.jerry.mekextras.common.integration.mekaf.")) {
                assertAllowed(leaf, MKAF_BASE, MEK_BASE);
            } else if (leaf.startsWith("com.jerry.mekextras.common.integration.mekmm.")) {
                assertAllowed(leaf, MKMM_BASE, MEK_BASE);
            } else {
                throw new AssertionError("Unexpected leaf: " + leaf);
            }
        }
    }

    /**
     * Addon machines extending an admitted class inherit none of this addon's
     * guarantees and must fail closed on the leaf check. A chain that leaves
     * both packages, or stops short of the mek base, fails the same way.
     */
    @Test
    void addonMachinesExtendingAdmittedClassesAreRefused() {
        assertRefused("com.example.somemod.tile.CustomCentrifugingFactory",
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraCentrifugingFactory",
                MKAF_BASE, MEK_BASE);
        // A chain that leaves both packages is not a licence either.
        assertFalse(MkxOffloadPolicy.chainAllowed(List.of(
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraCentrifugingFactory",
                "com.example.somemod.tile.SomeBase")));
        // A chain that stops short of the mek base is not either.
        assertFalse(MkxOffloadPolicy.chainAllowed(List.of(
                "com.jerry.mekextras.common.integration.mekaf.tile.factory.TileEntityExtraCentrifugingFactory",
                MKAF_BASE)));
        // Nor is an empty one.
        assertFalse(MkxOffloadPolicy.chainAllowed(List.of()));
    }

    private static void assertAllowed(String... chain) {
        assertTrue(MkxOffloadPolicy.chainAllowed(List.of(chain)),
                "expected offloadable: " + chain[0]);
    }

    private static void assertRefused(String... chain) {
        assertFalse(MkxOffloadPolicy.chainAllowed(List.of(chain)),
                "expected denied: " + chain[0]);
    }
}