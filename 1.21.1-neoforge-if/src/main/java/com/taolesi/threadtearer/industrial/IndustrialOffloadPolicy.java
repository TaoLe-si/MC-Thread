package com.taolesi.threadtearer.industrial;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Industrial Foregoing block-entity ticks may run on a compute worker.
 *
 * <p>Read against the real sources: IF at branch {@code 1.21} (jar 3.6.39)
 * and its framework Titanium at branch {@code 1.21} (jar 4.0.45). Every IF
 * machine is a Titanium {@code ActiveTile}; the whole per-machine difference
 * lives in {@code work()} or {@code onFinish()}, which the progress bar calls
 * when it fills. So unlike Mekanism — where one base class split the safe
 * machines from the unsafe — the split here is per machine, and the rule is a
 * <b>closed allowlist</b>: a machine is on it only after its {@code work()} /
 * {@code onFinish()} body (and everything they call) has been read and found
 * to touch nothing outside the machine's own slots, tanks and energy.
 *
 * <p>The neighbourhood auto-push every Titanium tick can do —
 * {@code IFacingComponent.work} over the sided inventories and tanks — is not
 * part of that judgement: {@code ActiveTileFacingWorkMixin} defers it back to
 * the server thread for every admitted machine, the same split the Mekanism
 * addon made around {@code TileComponentEjector.tickServer}.
 *
 * <p><b>Route B (this release) — Level-write area work.</b> IF 0.1.0
 * rejected the entire {@code IndustrialAreaWorkingTile} family on a single
 * "AABB scans + block placement + fake-player interaction" verdict. A
 * family-level audit split that verdict in two: machines whose area tick
 * touches <em>only</em> {@code Level.setBlock / Level.setBlockAndUpdate /
 * Level.removeBlock} (already intercepted by the core's
 * {@code LevelMixin} and forwarded to the interaction-thread FIFO when
 * called from a compute worker) plus their own slot/tank work, do in fact
 * fit the worker + interaction-thread split. They join the allowlist
 * alongside the original fourteen; the area machine's own
 * {@code IFacingComponent.work()} still defers to the server thread via
 * {@link IfDeferral}. The remaining twenty-or-so area machines call
 * {@code Entity.hurt / Entity.discard / Level.getEntitiesOfClass}, which are
 * not intercepted by the core and have not been audited safe to call from
 * a worker — they keep running on the server thread.
 *
 * <p><b>What was read, per family:</b>
 * <ul>
 *   <li><b>Admitted — processing machines</b> (pure own-state
 *       {@code onFinish()}): Dissolution Chamber, Latex Processing Unit,
 *       Dye Mixer, Fermentation Station, Fluid Sieving Machine, Washing
 *       Factory, Sewage Composter, Spores Recreator, Material StoneWork
 *       Factory, Potion Brewer, Enchantment Sorter, Resourceful Furnace
 *       ({@code registryAccess()} only), Enchantment Extractor
 *       ({@code EnchantmentHelper} is static over the machine's own
 *       stacks).</li>
 *   <li><b>Admitted — working machines</b>: Bio Reactor (its {@code work()}
 *       is own tanks and input slots only).</li>
 *   <li><b>Admitted — area machines under route B</b> (Level-write only,
 *       no entity / fake-player API): Block Breaker, Fluid Collector,
 *       Fluid Placer, Plant Sower, Hydroponic Bed (real), Simulated
 *       Hydroponic Bed. Their area tick uses only {@code setBlock} (or its
 *       subclasses) on the cached range; the core's {@code LevelMixin}
 *       defers those to the interaction-thread FIFO automatically.</li>
 *   <li><b>Refused — reads that feed arithmetic.</b> Water Condensator
 *       ({@code getWaterSources()} reads six neighbours' fluid state and the
 *       result sizes the fill), Enchantment Factory and Applicator (drain the
 *       fluid handler <em>above</em> the machine and the drained amount feeds
 *       the enchant level). A read whose value the tick consumes cannot be
 *       deferred — the tick would have to invent a number.</li>
 *   <li><b>Refused — shared RNG.</b> Sludge Refiner draws from
 *       {@code this.level.random}, the level-wide {@code RandomSource} the
 *       server thread itself uses; concurrent draws corrupt both
 *       sequences.</li>
 *   <li><b>Refused — entity touches (route B "no").</b> Plant Gatherer
 *       ({@code getEntitiesOfClass(ItemEntity)}), Plant Fertilizer
 *       (item-entity scan), Sewer ({@code getEntitiesOfClass(Mob)}),
 *       Mechanical Dirt (mob scan), Mob Crusher / Duplicator /
 *       Slaughter Factory (entity damage / removal), Animal Feeder /
 *       Rancher / Baby Separator (mob + breeding), Mob Detector (mob
 *       scan), Wither Builder (wither entity), Stasis Chamber (entity
 *       transfer), Marine Fisher (item entity), Laser Drill (mining
 *       pipeline touching {@code level.random} shared RNG). Each calls
 *       an entity / RNG API the core does not intercept and that has not
 *       been audited thread-safe on a worker.</li>
 *   <li><b>Refused — generators.</b> Every {@code GeneratorTile} pushes
 *       energy into six neighbours per tick and the received amount feeds the
 *       extract, the turbine emit-and-drain shape; not deferrable one-sided.
 *       Also Mycelial generators.</li>
 * </ul>
 *
 * <p>Redstone: {@code shouldWork()} for the four standard actions reads only
 * the cached {@code lastRedstoneState} (updated by the neighbour-change
 * event), and the detector-style actions read
 * {@code Level.getBestNeighborSignal}, a loaded-chunk blockstate read the
 * core already allows on compute threads.
 */
public final class IndustrialOffloadPolicy {

    /**
     * The closed allowlist. Each entry's {@code work()} / {@code onFinish()}
     * was read in the IF source; see the class javadoc for the per-family
     * verdicts and the refusal reasons for everything not here.
     */
    private static final Set<String> ALLOWED = Set.of(
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
            "com.buuz135.industrial.block.resourceproduction.tile.WashingFactoryTile",
            // Route B — area machines whose tick uses only Level.setBlock /
            // Level.removeBlock (auto-forwarded by core LevelMixin) and own
            // slot work. Their IFacingComponent.work() still defers to the
            // server thread via IfDeferral; the area-tick compute runs on
            // the worker.
            "com.buuz135.industrial.block.resourceproduction.tile.BlockBreakerTile",
            "com.buuz135.industrial.block.resourceproduction.tile.FluidCollectorTile",
            "com.buuz135.industrial.block.resourceproduction.tile.FluidPlacerTile",
            "com.buuz135.industrial.block.agriculturehusbandry.tile.PlantSowerTile",
            "com.buuz135.industrial.block.agriculturehusbandry.tile.HydroponicBedTile",
            "com.buuz135.industrial.block.agriculturehusbandry.tile.SimulatedHydroponicBedTile"
    );

    /**
     * The only two packages a real chain may pass through. The IF half is
     * {@code IndustrialProcessingTile → IndustrialMachineTile}; from there the
     * rest belongs to Titanium ({@code MachineTile → PoweredTile → ActiveTile
     * → BasicTile}), so a walk that insists on IF all the way up rejects every
     * machine that exists.
     */
    private static final String IF_PACKAGE = "com.buuz135.industrial.";
    private static final String TITANIUM_PACKAGE = "com.hrznstudio.titanium.";

    /** Titanium's base — the chain must reach it to be an IF machine at all. */
    private static final String TITANIUM_BASE = "com.hrznstudio.titanium.block.tile.BasicTile";

    /** Per-class verdict cache: the check runs for every BE every tick. */
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private IndustrialOffloadPolicy() {
    }

    /** True when this block entity's tick may run on a compute thread. */
    public static boolean mayOffload(BlockEntity be) {
        if (be == null || DISABLED) {
            return false;
        }
        return CACHE.computeIfAbsent(be.getClass(), IndustrialOffloadPolicy::verdict);
    }

    /**
     * The rule itself, as a pure function of a superclass chain ordered from
     * the concrete class upwards. Split out so the policy can be pinned by a
     * test without a Mekanism-equivalent classpath, exactly as the mek
     * addon's {@code chainAllowed} is.
     *
     * <p>The leaf must be on {@link #ALLOWED}; from there the walk must reach
     * Titanium's {@code BasicTile} without ever leaving the IF or Titanium
     * packages. An addon machine extending an admitted class fails on the leaf
     * — its own name is not on the list — and a chain that stops short of the
     * Titanium base is not an IF machine either.
     */
    static boolean chainAllowed(List<String> superChain) {
        if (superChain.isEmpty() || !ALLOWED.contains(superChain.get(0))) {
            return false;
        }
        for (int i = 1; i < superChain.size(); i++) {
            String name = superChain.get(i);
            if (TITANIUM_BASE.equals(name)) {
                return true;
            }
            if (name == null
                    || !(name.startsWith(IF_PACKAGE) || name.startsWith(TITANIUM_PACKAGE))) {
                return false;
            }
        }
        return false;
    }

    /**
     * Exact-class allow plus the same package walk as {@link #chainAllowed},
     * over the live superclass chain.
     */
    private static boolean verdict(Class<?> type) {
        if (!ALLOWED.contains(type.getName())) {
            return false;
        }
        for (Class<?> c = type.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (TITANIUM_BASE.equals(name)) {
                return true;
            }
            if (!(name.startsWith(IF_PACKAGE) || name.startsWith(TITANIUM_PACKAGE))) {
                return false;
            }
        }
        return false;
    }

    /** The audited allowlist, exposed so a test can pin it. */
    static Set<String> allowedClasses() {
        return ALLOWED;
    }

    /** The allowlist size, exposed for the startup log line. */
    public static int allowedSize() {
        return ALLOWED.size();
    }

    /**
     * Global kill switch. Set {@code -Dthreadtearer.industrial.offload=false}
     * to make every IF tick run on the server thread.
     */
    private static final boolean DISABLED =
            "false".equalsIgnoreCase(System.getProperty("threadtearer.industrial.offload", "true"));

    /** True when the kill switch is active (for the startup log line). */
    public static boolean disabled() {
        return DISABLED;
    }
}