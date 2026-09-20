package com.taolesi.threadtearer.industrial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the offload allowlist for Industrial Foregoing.
 *
 * <p>Unlike the Mekanism policy — one base class separated safe machines
 * from unsafe, so a chain rule could carry the verdict — every IF machine
 * differs in its own {@code work()} / {@code onFinish()} body. The rule is
 * therefore a closed allowlist, and this test is what stops it from being
 * edited into something the source reading did not support.
 *
 * <p>The chains below are the real superclass chains read out of the IF and
 * Titanium sources (IF 3.6.39, Titanium 4.0.45). A processing machine's chain
 * is {@code IndustrialProcessingTile → IndustrialMachineTile} on the IF side
 * and {@code MachineTile → PoweredTile → ActiveTile → BasicTile} on the
 * Titanium side; generators reach Titanium one level higher, through
 * {@code GeneratorTile}; area machines share the same Titanium chain via
 * {@code IndustrialWorkingTile → IndustrialAreaWorkingTile}. Writing them
 * from the sources matters: a chain invented from memory passes the same
 * assertions while describing a class that does not exist — the mistake the
 * Mekanism policy test was rewritten to prevent.
 *
 * <p>Route B — area machines whose tick touches only Level-write APIs
 * (auto-forwarded by the core) are admitted. The remaining area machines,
 * which call entity / shared-RNG APIs the core does not intercept, keep
 * running on the server thread.
 */
class IndustrialOffloadPolicyTest {

    /** The real chain suffix of every IF machine, read from the sources. */
    private static final String IF_MACHINE = "com.buuz135.industrial.block.tile.IndustrialMachineTile";
    private static final String PROCESSING = "com.buuz135.industrial.block.tile.IndustrialProcessingTile";
    private static final String WORKING = "com.buuz135.industrial.block.tile.IndustrialWorkingTile";
    private static final String AREA = "com.buuz135.industrial.block.tile.IndustrialAreaWorkingTile";
    private static final String GENERATOR = "com.buuz135.industrial.block.tile.IndustrialGeneratorTile";
    private static final String MACHINE = "com.hrznstudio.titanium.block.tile.MachineTile";
    private static final String POWERED = "com.hrznstudio.titanium.block.tile.PoweredTile";
    private static final String ACTIVE = "com.hrznstudio.titanium.block.tile.ActiveTile";
    private static final String BASIC = "com.hrznstudio.titanium.block.tile.BasicTile";
    private static final String GENERATOR_TITANIUM = "com.hrznstudio.titanium.block.tile.GeneratorTile";

    /**
     * The allowlist is exactly the machines whose work bodies were read and
     * found pure. Adding one is a source-reading task first and a one-line
     * change second; the count makes an accidental edit a test failure.
     */
    @Test
    void allowListIsExactlyTheAuditedMachines() {
        assertEquals(Set.of(
                "com.buuz135.industrial.block.core.tile.DissolutionChamberTile",
                "com.buuz135.industrial.block.core.tile.LatexProcessingUnitTile",
                "com.buuz135.industrial.block.generator.tile.BioReactorTile",
                "com.buuz135.industrial.block.misc.tile.EnchantmentExtractorTile",
                "com.buuz135.industrial.block.misc.tile.EnchantmentSorterTile",
                "com.buuz135.industrial.block.agriculturehusbandry.tile.SewageComposterTile",
                "com.buuz135.industrial.block.resourceproduction.tile.DyeMixerTile",
                "com.buuz135.industrial.block.resourceproduction.tile.FermentationStationTile",
                "com.buuz135.industrial.block.resourceproduction.tile.FluidSievingMachineTile",
                "com.buuz135.industrial.block.resourceproduction.tile.MaterialStoneWorkFactoryTile",
                "com.buuz135.industrial.block.resourceproduction.tile.PotionBrewerTile",
                "com.buuz135.industrial.block.resourceproduction.tile.ResourcefulFurnaceTile",
                "com.buuz135.industrial.block.resourceproduction.tile.SporesRecreatorTile",
                "com.buuz135.industrial.block.resourceproduction.tile.WashingFactoryTile"),
                IndustrialOffloadPolicy.allowedClasses());
        assertEquals(14, IndustrialOffloadPolicy.allowedSize());
    }

    /**
     * The admitted machines, each through its real chain. Processing machines
     * share one chain shape; the Bio Reactor walks through the working base.
     */
    @Test
    void admittedMachinesAreOffloadedThroughTheirRealChains() {
        for (String leaf : IndustrialOffloadPolicy.allowedClasses()) {
            if (leaf.endsWith("BioReactorTile")) {
                assertAllowed(leaf, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
            } else {
                assertAllowed(leaf, PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
            }
        }
    }

    /**
     * The refusals, each with the reason the source gave. Water Condensator
     * sizes its fill from six neighbours' fluid state; Enchantment Factory
     * and Applicator drain the handler above them and feed the amount into
     * the enchant level; Sludge Refiner draws from {@code level.random}.
     *
     * <p>The six route-B candidates are pinned here with the reason the
     * full-body read found — a pattern-count grep admitted them once, and
     * that audit was wrong:
     * <ul>
     *   <li>Block Breaker / Fluid Collector / Fluid Placer all call
     *       {@code BlockUtils.canBlockBeBroken}, which builds a cached
     *       FakePlayer and posts {@code BlockEvent.BreakEvent} on the
     *       NeoForge event bus — protection-mod handlers would run on the
     *       worker, and the per-owner fake player is shared mutable
     *       state.</li>
     *   <li>Hydroponic Bed and Simulated Hydroponic Bed draw from
     *       {@code level.random}, the level-wide RandomSource the server
     *       thread's own random ticks use.</li>
     *   <li>Plant Sower dispatches to {@code SpecialPlantable}, a
     *       third-party interface whose implementations run on the calling
     *       thread.</li>
     * </ul>
     */
    @Test
    void worldReadingAndSharedStateMachinesAreRefused() {
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.WaterCondensatorTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.misc.tile.EnchantmentFactoryTile",
                PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.misc.tile.EnchantmentApplicatorTile",
                PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.SludgeRefinerTile",
                PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        // Area machines that DO call entity / shared-RNG APIs — pinned here
        // so a careless edit can't admit them back into the allowlist.
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.SewerTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.PlantGathererTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.MobCrusherTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.MobDuplicatorTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.SlaughterFactoryTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.AnimalFeederTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.AnimalRancherTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.AnimalBabySeparatorTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.MechanicalDirtTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.MarineFisherTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.LaserDrillTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.MobDetectorTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.PlantFertilizerTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        // The six route-B candidates — admitted once on a grep, refused on
        // the full-body read (fake player + event bus, level.random,
        // SpecialPlantable). Pinned so the same grep doesn't re-admit them.
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.BlockBreakerTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.FluidCollectorTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.resourceproduction.tile.FluidPlacerTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.PlantSowerTile",
                AREA, WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile",
                WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused("com.buuz135.industrial.block.agriculturehusbandry.tile.SimulatedHydroponicBedTile",
                WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        // Generators: six-neighbour energy push, extract feeds receive.
        assertRefused("com.buuz135.industrial.block.generator.tile.BiofuelGeneratorTile",
                GENERATOR, GENERATOR_TITANIUM, POWERED, ACTIVE, BASIC);
        // And the base classes themselves are never admitted.
        assertRefused(PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        assertRefused(WORKING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
    }

    /**
     * An addon machine extending an admitted IF class inherits none of this
     * addon's guarantees and must fail closed on the leaf check.
     */
    @Test
    void addonMachinesExtendingAdmittedClassesAreRefused() {
        assertRefused("com.example.somemod.tile.CustomDissolutionChamberTile",
                "com.buuz135.industrial.block.core.tile.DissolutionChamberTile",
                PROCESSING, IF_MACHINE, MACHINE, POWERED, ACTIVE, BASIC);
        // A chain that leaves both known packages is not a licence either.
        assertFalse(IndustrialOffloadPolicy.chainAllowed(List.of(
                "com.buuz135.industrial.block.core.tile.DissolutionChamberTile",
                PROCESSING, "com.example.somemod.tile.SomeBase")));
        // A chain that never reaches the Titanium base is not either.
        assertFalse(IndustrialOffloadPolicy.chainAllowed(List.of(
                "com.buuz135.industrial.block.core.tile.DissolutionChamberTile",
                PROCESSING, IF_MACHINE)));
        // Nor is an empty one.
        assertFalse(IndustrialOffloadPolicy.chainAllowed(List.of()));
    }

    private static void assertAllowed(String... chain) {
        assertTrue(IndustrialOffloadPolicy.chainAllowed(List.of(chain)),
                "expected offloadable: " + chain[0]);
    }

    private static void assertRefused(String... chain) {
        assertFalse(IndustrialOffloadPolicy.chainAllowed(List.of(chain)),
                "expected denied: " + chain[0]);
    }
}