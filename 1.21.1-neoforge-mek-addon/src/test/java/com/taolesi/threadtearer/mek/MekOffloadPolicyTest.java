package com.taolesi.threadtearer.mek;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the offload verdict for every audited Mekanism block entity.
 *
 * <p>Each chain below is the real superclass chain read out of the installed
 * Mekanism jars (Mekanism 10.7.19.85, MekanismGenerators 10.7.19.85, plus every
 * Mekanism addon in the pack), concrete class first, up to
 * {@code TileEntityMekanism}. Writing them from the jars rather than from memory
 * matters: an earlier revision of this test used chains that looked plausible —
 * {@code TileEntityResistiveHeater} under
 * {@code TileEntityConfigurableMachine} — and passed, while the class it
 * described does not have that parent.
 *
 * <p>The rule the tests pin has two halves, and the second is the one that took
 * three revisions to get right:
 *
 * <ol>
 *   <li><b>The shape rule.</b> The chain must contain one of the three covered
 *       shapes, each of which has a defer mixin that hands the world-reaching
 *       call back to the server thread:
 *       {@code TileEntityConfigurableMachine} (ejector push deferred by
 *       {@code TileEntityConfigurableMachineEjectMixin}),
 *       {@code TileEntityGenerator} (cable emit deferred, readiness-gated on
 *       {@code outputCaches}), or one of the two {@code HEATER_SHAPES}
 *       (whole {@code simulate()} deferred). Without a covered shape an
 *       offloaded tick would push into its neighbours or run a heat exchange
 *       from a compute worker.</li>
 *   <li><b>The package rule.</b> Every class in the chain must be
 *       {@code mekanism.} proper, so third-party machines built on the same base
 *       classes — which reach AE2 and other non-thread-safe networks — fail
 *       closed.</li>
 * </ol>
 *
 * <p>The population that survives is 40 of the 327 installed descendants: the
 * recipe machines, the factories, the chemical machines, the energy cube, the
 * chemical tank, the six abstract bases they share, the generators
 * (readiness-gated on {@code outputCaches}), and the two heat simulators
 * (whose simulate-defer mixin runs the whole exchange under the tile lock).
 * Everything else has a trivial or empty tick or shared mutable state that a
 * per-call defer cannot make thread-safe, so refusing it costs nothing
 * measurable.
 *
 * <p>Multiblocks are a third case: refused at the tile level by this policy, but
 * their simulation is offloaded at the data level by
 * {@code TileEntityMultiblockDataTickMixin}, which wraps the single
 * {@code MultiblockData.tick(level)} call inside
 * {@code TileEntityMultiblock.onUpdateServer} rather than the whole method. See
 * {@link #multiblockTilesStayOnTheServerThread()}.
 */
class MekOffloadPolicyTest {

    /** {@code TileEntityMekanism} is the root every offloadable chain reaches. */
    private static final String BASE = "mekanism.common.tile.base.TileEntityMekanism";

    /** The shape the ejector defer covers. */
    private static final String SHAPE = "mekanism.common.tile.prefab.TileEntityConfigurableMachine";

    /** The three multiblock BE shapes the per-family emit defer mixins cover. */
    private static final String MULTI = "mekanism.common.tile.prefab.TileEntityMultiblock";
    private static final String INTERNAL = "mekanism.common.tile.prefab.TileEntityInternalMultiblock";
    private static final String STRUCTURAL = "mekanism.common.tile.prefab.TileEntityStructuralMultiblock";

    /** The two heater tiles the simulate-defer mixins cover. */
    private static final String RESISTIVE = "mekanism.common.tile.machine.TileEntityResistiveHeater";
    private static final String FUELWOOD = "mekanism.common.tile.machine.TileEntityFuelwoodHeater";

    /**
     * The exact set of installed classes whose tick is offloaded. Every chain
     * here was read from the jars.
     *
     * <p>This is the load-bearing test: if a Mekanism update reparents a class,
     * moves one out of the {@code mekanism.} package, or adds a new machine, the
     * population changes and this list stops matching. The count assertion at the
     * end makes a silent shrink — the failure mode that cost two revisions — a
     * test failure instead.
     *
     * <p>{@code TileEntityEnrichmentChamber} and {@code TileEntityCrusher} are
     * named explicitly because they are the concrete machines the first
     * revision's name-substring matcher dropped.
     */
    @Test
    void offloadablePopulationIsExactlyTheseChains() {
        assertOffloaded("mekanism.common.tile.machine.TileEntityEnrichmentChamber",
                "mekanism.common.tile.prefab.TileEntityElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityCrusher",
                "mekanism.common.tile.prefab.TileEntityElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityEnergizedSmelter",
                "mekanism.common.tile.prefab.TileEntityElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalInjectionChamber",
                "mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityOsmiumCompressor",
                "mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPurificationChamber",
                "mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityMetallurgicInfuser",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityCombiner",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPaintingMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPigmentExtractor",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPrecisionSawmill",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPressurizedReactionChamber",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityAntiprotonicNucleosynthesizer",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalCrystallizer",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalDissolutionChamber",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalOxidizer",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityNutritionalLiquifier",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalInfuser",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityChemicalWasher",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityElectrolyticSeparator",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityIsotopicCentrifuge",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityPigmentMixer",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.machine.TileEntityRotaryCondensentrator",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        // TileEntitySolarNeutronActivator is NOT here: it reads the sun brightness
        // in its own tick and stores the result. See denyListIsPinned.
        // Oredictionificator sits straight on the shape: its tick is slots and
        // the filter manager, nothing else.
        assertOffloaded("mekanism.common.tile.machine.TileEntityOredictionificator", SHAPE, BASE);

        // Factories run one shared onUpdateServer for every tier and type.
        assertOffloaded("mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.factory.TileEntityItemToItemFactory",
                "mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.factory.TileEntityItemStackToItemStackFactory",
                "mekanism.common.tile.factory.TileEntityItemToItemFactory",
                "mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.factory.TileEntityItemStackChemicalToItemStackFactory",
                "mekanism.common.tile.factory.TileEntityItemToItemFactory",
                "mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.factory.TileEntitySawingFactory",
                "mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.factory.TileEntityCombiningFactory",
                "mekanism.common.tile.factory.TileEntityItemToItemFactory",
                "mekanism.common.tile.factory.TileEntityFactory", SHAPE, BASE);

        // Passive storage inside the deferred shape.
        assertOffloaded("mekanism.common.tile.TileEntityEnergyCube", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.TileEntityChemicalTank", SHAPE, BASE);

        // The abstract bases themselves, for completeness.
        assertOffloaded(SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.prefab.TileEntityElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
        assertOffloaded("mekanism.common.tile.prefab.TileEntityAdvancedElectricMachine",
                "mekanism.common.tile.prefab.TileEntityProgressMachine",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine", SHAPE, BASE);
    }

/**
     * The families an audit of all 327 installed descendants found reaching into
     * the world, each with the call that keeps it on the server thread. Almost
     * all of these are refused by the <em>shape</em> rule — they extend neither
     * {@code TileEntityConfigurableMachine} nor {@code TileEntityGenerator} —
     * which is why the deny list itself only needs seven entries.
     */
    @Test
    void worldReachingFamiliesAreRefusedByTheShapeRule() {
        // Neighbour capability cache + emit.
        assertDenied("mekanism.common.tile.TileEntityBin", BASE);
        assertDenied("mekanism.common.tile.TileEntityFluidTank", BASE);
        assertDenied("mekanism.common.tile.TileEntityRadioactiveWasteBarrel", BASE);
        assertDenied("mekanism.common.tile.TileEntityLogisticalSorter", BASE);
        assertDenied("mekanism.common.tile.machine.TileEntityDigitalMiner", BASE);
        assertDenied("mekanism.common.tile.machine.TileEntityElectricPump", BASE);

        // The teleport itself.
        assertDenied("mekanism.common.tile.TileEntityTeleporter", BASE);
        // AABB entity scan + per-entity capability lookup.
        assertDenied("mekanism.common.tile.TileEntityChargepad", BASE);
        // Places fluid blocks.
        assertDenied("mekanism.common.tile.machine.TileEntityFluidicPlenisher", BASE);
        // Broadcasts a sculk game event.
        assertDenied("mekanism.common.tile.machine.TileEntitySeismicVibrator", BASE);
        // Issues chunk tickets.
        assertDenied("mekanism.common.tile.machine.TileEntityDimensionalStabilizer", BASE);
        // Reads the shared security frequency and stamps its mode onto a slot's
        // item stack.
        assertDenied("mekanism.common.tile.TileEntitySecurityDesk", BASE);
    }

    /**
     * The two heater tiles, whose only world reach is
     * {@code ITileHeatHandler.simulate()}: the heat exchange (neighbour reads
     * and {@code sink.handleHeat} writes into {@code BasicHeatCapacitor}, a
     * plain double without synchronization). The simulate-defer mixin runs
     * the whole exchange under the tile lock on the server thread, so the
     * worker side is just energy bookkeeping.
     *
     * <p>This is the third shape the addon's policy admits. It is a direct
     * FQCN rather than a chain anchor because the only correct call site to
     * defer is on these two concrete classes — no descendant of either
     * heater exists, and no other class implements the same heat-exchange
     * tick body.
     */
    @Test
    void heatersAreOffloadedThroughSimulateDefer() {
        assertOffloaded(RESISTIVE, BASE);
        assertOffloaded(FUELWOOD, BASE);
    }

    /**
     * The generator family, split in two.
     *
     * <p>{@code TileEntityGenerator} is the addon's second covered shape:
     * {@code TileEntityGeneratorEmitMixin} defers the {@code CableUtils.emit}
     * that pushes into the neighbour energy handlers, and the policy holds the
     * tick on the server thread until {@code outputCaches} exists, because
     * building it registers a capability listener.
     *
     * <p>The two members whose own body is only tank / slot / container
     * arithmetic move. The four that read the level from their own body do not,
     * and they are refused by name rather than by shape — a shape rule cannot
     * separate them, because the read is in the subclass, not in the base.
     */
    @Test
    void generatorsAreSplitIntoMovingAndRefusedMembers() {
        // Base shape is covered; it has no tick body of its own beyond the emit.
        assertOffloaded("mekanism.generators.common.tile.TileEntityGenerator", BASE);

        // Bio: fluid tank + energy slot + BasicEnergyContainer.insert.
        assertOffloaded("mekanism.generators.common.tile.TileEntityBioGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);
        // Gas: FuelTank + chemical slot + generationRate arithmetic.
        assertOffloaded("mekanism.generators.common.tile.TileEntityGasGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);

        // getBoost -> WorldUtils.getFluidState x6 neighbours + ultraWarm.
        assertDenied("mekanism.generators.common.tile.TileEntityHeatGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);
        // checkCanSeeSun -> SolarCheck -> WorldUtils.canSeeSun.
        assertDenied("mekanism.generators.common.tile.TileEntitySolarGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);
        assertDenied("mekanism.generators.common.tile.TileEntityAdvancedSolarGenerator",
                "mekanism.generators.common.tile.TileEntitySolarGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);
        // getMultiplier -> Level.getFluidState / canSeeSky / dimensionType.
        assertDenied("mekanism.generators.common.tile.TileEntityWindGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE);
    }

    /**
     * AABB entity scan, entity damage, block breaking, fake player. The chain
     * carries the verdict up from {@code TileEntityBasicLaser}, so the amplifier
     * and the tractor beam are refused even though their own bodies look
     * innocent.
     */
    @Test
    void laserFamilyIsDeniedByItsBaseClass() {
        assertDenied("mekanism.common.tile.laser.TileEntityBasicLaser", BASE);
        assertDenied("mekanism.common.tile.laser.TileEntityLaser",
                "mekanism.common.tile.laser.TileEntityBasicLaser", BASE);
        assertDenied("mekanism.common.tile.laser.TileEntityLaserReceptor",
                "mekanism.common.tile.laser.TileEntityBasicLaser", BASE);
        assertDenied("mekanism.common.tile.laser.TileEntityLaserAmplifier",
                "mekanism.common.tile.laser.TileEntityLaserReceptor",
                "mekanism.common.tile.laser.TileEntityBasicLaser", BASE);
        assertDenied("mekanism.common.tile.laser.TileEntityLaserTractorBeam",
                "mekanism.common.tile.laser.TileEntityLaserReceptor",
                "mekanism.common.tile.laser.TileEntityBasicLaser", BASE);
    }

    /**
     * Multiblocks are refused at the tile level and offloaded at the data
     * level instead.
     *
     * <p>{@code TileEntityMultiblock.onUpdateServer} is the formation protocol,
     * the shared {@code MultiblockManager} queues, the comparator
     * notifications and the subclass neighbour updates wrapped around the
     * simulation, so the blanket tile steal
     * ({@code TileEntityMekanismTickMixin}) must not take it. The simulation
     * itself — {@code MultiblockData.tick(level)} — is stolen on its own by
     * {@code TileEntityMultiblockDataTickMixin}, and the per-family
     * {@code Util.emit} call sites inside it are deferred back by the
     * {@code *MultiblockDataEjectMixin} family.
     *
     * <p>This test pins the tile-level verdict. The data-level side is pinned by
     * {@link #multiblockDataClassesAreExactlyTheDeferredFamilies()}, which reads
     * the same list the policy consults at run time.
     */
    @Test
    void multiblockTilesStayOnTheServerThread() {
        assertDenied(MULTI, BASE);
        assertDenied(INTERNAL, BASE);
        assertDenied(STRUCTURAL, BASE);

        // Casings / valves / cells / providers are descendants of one of the
        // three multiblock base shapes — they inherit the refusal.
        assertDenied("mekanism.common.tile.multiblock.TileEntityBoilerCasing",
                MULTI, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntityBoilerValve",
                "mekanism.common.tile.multiblock.TileEntityBoilerCasing",
                MULTI, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntityDynamicTank",
                MULTI, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntityInductionCasing",
                MULTI, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntityInductionCell",
                INTERNAL, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntitySuperheatingElement",
                INTERNAL, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntitySPSCasing",
                MULTI, BASE);
        assertDenied("mekanism.common.tile.multiblock.TileEntityThermalEvaporationController",
                "mekanism.common.tile.multiblock.TileEntityThermalEvaporationBlock",
                MULTI, BASE);
    }

    /**
     * The data-level half of the multiblock rule: exactly the families a
     * per-family defer mixin covers, and nothing else.
     *
     * <p>This list is not a copy of the policy's — it is read back out of the
     * policy, so the assertion is that the set is the six audited families. A
     * seventh entry means someone added a family without a mixin deferring its
     * neighbour pushes, which is the failure this test exists to catch. Adding
     * one deliberately means adding the mixin and then the entry here.
     *
     * <p>{@code TankMultiblockData} and {@code EvaporationMultiblockData} are
     * named as absences. Their ticks were read and do not reach a neighbour, but
     * neither has a defer mixin, so admitting them would be an unaudited
     * promise; the entry is what would have to change, not the tick.
     */
    @Test
    void multiblockDataClassesAreExactlyTheDeferredFamilies() {
        assertEquals(java.util.Set.of(
                        "mekanism.common.content.boiler.BoilerMultiblockData",
                        "mekanism.common.content.matrix.MatrixMultiblockData",
                        "mekanism.common.content.sps.SPSMultiblockData",
                        "mekanism.generators.common.content.turbine.TurbineMultiblockData",
                        "mekanism.generators.common.content.fission.FissionReactorMultiblockData",
                        "mekanism.generators.common.content.fusion.FusionReactorMultiblockData"),
                MekOffloadPolicy.multiblockDataClasses());

        assertFalse(MekOffloadPolicy.multiblockDataClasses()
                .contains("mekanism.common.content.tank.TankMultiblockData"));
        assertFalse(MekOffloadPolicy.multiblockDataClasses()
                .contains("mekanism.common.content.evaporation.EvaporationMultiblockData"));
    }

    /**
     * The QIO network. {@code TileEntityQIOComponent} does not extend the
     * deferred shape, so the shape rule already refuses every part; the chain
     * cases below pin that a future move of the base class cannot silently let
     * them back in.
     */
    @Test
    void qioNetworkStaysOnTheServerThread() {
        assertDenied("mekanism.common.tile.qio.TileEntityQIOComponent", BASE);
        assertDenied("mekanism.common.tile.qio.TileEntityQIOFilterHandler",
                "mekanism.common.tile.qio.TileEntityQIOComponent", BASE);
        assertDenied("mekanism.common.tile.qio.TileEntityQIOExporter",
                "mekanism.common.tile.qio.TileEntityQIOFilterHandler",
                "mekanism.common.tile.qio.TileEntityQIOComponent", BASE);
    }

    /**
     * The walk stops at {@link #BASE} and ignores everything above it. Above
     * the Mekanism base the chain is {@code CapabilityTileEntity} →
     * {@code net.minecraft.world.level.block.entity.BlockEntity} → {@code
     * net.neoforged.neoforge.attachment.AttachmentHolder} → {@code Object},
     * none of which is in the {@code mekanism.} package. If the walk continued,
     * every verdict would fail on the package check and the addon would
     * silently offload nothing — which is the regression the in-game
     * diagnostic caught in 0.3.1 / 0.3.2.
     */
    @Test
    void walkStopsAtTheMekanismBaseAndIgnoresTheVanillaAncestorsAboveIt() {
        // A typical recipe machine, real chain from the installed jar.
        assertOffloaded("mekanism.common.tile.machine.TileEntityElectrolyticSeparator",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine",
                "mekanism.common.tile.prefab.TileEntityConfigurableMachine", BASE);
        // Same chain with the vanilla ancestors that follow TileEntityMekanism
        // tacked on, as the production walk would supply them.
        assertOffloaded("mekanism.common.tile.machine.TileEntityElectrolyticSeparator",
                "mekanism.common.tile.prefab.TileEntityRecipeMachine",
                "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
                BASE,
                "mekanism.common.tile.base.CapabilityTileEntity",
                "net.minecraft.world.level.block.entity.BlockEntity",
                "net.neoforged.neoforge.attachment.AttachmentHolder",
                "java.lang.Object");
        // A factory chain, with the same vanilla ancestors.
        assertOffloaded("mekanism.common.tile.factory.TileEntityItemToItemFactory",
                "mekanism.common.tile.factory.TileEntityFactory",
                "mekanism.common.tile.prefab.TileEntityConfigurableMachine",
                BASE,
                "mekanism.common.tile.base.CapabilityTileEntity",
                "net.minecraft.world.level.block.entity.BlockEntity");
        // A class that does NOT reach either covered shape still fails closed
        // even if the chain keeps going past the base — the package check is only
        // applied up to and including TileEntityMekanism.
        assertDenied("mekanism.common.tile.TileEntityBin",
                BASE,
                "mekanism.common.tile.base.CapabilityTileEntity",
                "net.minecraft.world.level.block.entity.BlockEntity");
        // And so does a generator that is refused by name, rather than by shape.
        assertDenied("mekanism.generators.common.tile.TileEntityWindGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator",
                BASE,
                "mekanism.common.tile.base.CapabilityTileEntity",
                "net.minecraft.world.level.block.entity.BlockEntity");
    }

    /**
     * The two audited exceptions <em>inside</em> the deferred shape. The shape
     * rule is the only thing that normally keeps a world-reaching tick on the
     * server thread, so these two have to be named.
     */
    @Test
    void auditedExceptionsInsideTheDeferredShapeAreDenied() {
        // InventoryFrequency.handleEject walks every other entangloporter in the
        // frequency and pushes into their neighbours; simulate() reads neighbour
        // heat handlers.
        assertDenied("mekanism.common.tile.TileEntityQuantumEntangloporter", SHAPE, BASE);
        // moveItemsToGrid / organizeStock read the level through the formula and
        // recipe API and write the crafting grid.
        assertDenied("mekanism.common.tile.machine.TileEntityFormulaicAssemblicator", SHAPE, BASE);
    }

    /**
     * Every shape this addon has never seen fails closed: a chain that leaves the
     * {@code mekanism.} package, a chain that never reaches the deferred shape, a
     * chain that never reaches the Mekanism base, and the empty chain.
     */
    @Test
    void unknownAndForeignChainsFailClosed() {
        assertFalse(MekOffloadPolicy.chainAllowed(List.of()));
        // Reaches the Mekanism base but not the deferred shape.
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "mekanism.common.tile.machine.TileEntitySomethingNew", BASE)));
        // Reaches the deferred shape but not the Mekanism base.
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "mekanism.common.tile.machine.TileEntitySomethingNew", SHAPE)));
        // A null link in the chain (a class whose superclass is not on the
        // classpath) is not a licence to offload.
        assertFalse(MekOffloadPolicy.chainAllowed(java.util.Arrays.asList(
                "mekanism.common.tile.machine.TileEntitySomethingNew", null, BASE)));

        // Leaves the Mekanism package: the AE2-backed machine that froze the
        // game, and every other addon shape built on the same base classes.
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.beipuo.mekenergistics.blockentity.machine.utility.MeDigitalMinerBlockEntity",
                "mekanism.common.tile.machine.TileEntityDigitalMiner", BASE)));
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.jerry.mekextras.common.tile.TileEntityExtraBin",
                "mekanism.common.tile.TileEntityBin", BASE)));
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.jerry.mekextras.common.tile.factory.TileEntityExtraFactory", SHAPE, BASE)));
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.jerry.mekmm.common.tile.prefab.TileEntityMoreMachineGenerator", SHAPE, BASE)));
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.hamburger0abcde.mekanismsun.common.tiles.storage.TileEntityAdvanceBin",
                "mekanism.common.tile.TileEntityBin", BASE)));
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(
                "com.CompactMekanismMachines.common.tile.TileEntityCompressedWindGenerator",
                "mekanism.generators.common.tile.TileEntityGenerator", BASE)));
    }

    /**
     * The deny list is what a future editor is most likely to shrink by accident.
     * This pins its size and its members. The shape rule carries the rest of the
     * safety, which is why the list is only seven entries now.
     */
    @Test
    void denyListIsPinned() {
        assertEquals(7, MekOffloadPolicy.deniedSize());
        assertEquals(7, MekOffloadPolicy.deniedClasses().size());
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.common.tile.TileEntityQuantumEntangloporter"));
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.common.tile.machine.TileEntityFormulaicAssemblicator"));
        // Reads WorldUtils.getSunBrightness inside its own tick body and feeds
        // the result into productionRate, so it cannot be deferred.
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.common.tile.machine.TileEntitySolarNeutronActivator"));
        // The four generators whose own tick body reads the level. A shape rule
        // cannot separate them from their moving siblings — the read is in the
        // subclass, not in the covered base — so they are named.
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.generators.common.tile.TileEntityHeatGenerator"));
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.generators.common.tile.TileEntitySolarGenerator"));
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.generators.common.tile.TileEntityAdvancedSolarGenerator"));
        assertTrue(MekOffloadPolicy.deniedClasses().contains(
                "mekanism.generators.common.tile.TileEntityWindGenerator"));
        // The shapes the two defers cover must be the shapes the policy requires.
        assertEquals(SHAPE, MekOffloadPolicy.deferredShape());
        assertEquals("mekanism.generators.common.tile.TileEntityGenerator",
                MekOffloadPolicy.generatorShape());
    }

    private static void assertOffloaded(String... chain) {
        assertTrue(MekOffloadPolicy.chainAllowed(List.of(chain)),
                "expected offloadable: " + chain[0]);
    }

    private static void assertDenied(String... chain) {
        assertFalse(MekOffloadPolicy.chainAllowed(List.of(chain)),
                "expected denied: " + chain[0]);
    }
}
