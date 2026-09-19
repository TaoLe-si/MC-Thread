package com.taolesi.threadtearer.mek;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Mekanism block-entity ticks may run on a compute worker.
 *
 * <p><b>Three revisions of this class were wrong, and each mistake was a
 * different kind of wrong. The history is the reason the rule is shaped the way
 * it is.</b>
 *
 * <p>The first matched a lower-case substring of the concrete class name against
 * a whitelist of <em>base</em> classes. Mekanism names its machines after the
 * recipe, not the base class — {@code TileEntityEnrichmentChamber},
 * {@code TileEntityCrusher}, {@code TileEntityItemToItemFactory} — so none of
 * them matched {@code "tileentityelectricmachine"}. The audit was right and the
 * matcher silently discarded almost all of it: the readout showed
 * {@code computeTicks=7} per 100 ticks, about 0.1% of a tick budget.
 *
 * <p>The second kept that mistake and added an "is this machine ejecting into a
 * neighbour" gate on top. In a real base most machines <em>do</em> have an
 * output side configured, because a machine has to push its products somewhere,
 * so the gate removed the few survivors.
 *
 * <p>The third keyed on the superclass chain and allowed everything in the
 * {@code mekanism.} package except a 17-entry deny list. That fixed the
 * population but left a hole: only descendants of
 * {@link #DEFERRED_SHAPE} have their world-reaching half handed back to the
 * server thread by {@code TileEntityConfigurableMachineEjectMixin}, and
 * MekanismGenerators' generators reach into neighbours <em>from their own tick
 * body</em> — {@code TileEntityGenerator.onUpdateServer} lazily builds a
 * {@code BlockEnergyCapabilityCache} bound to the {@code ServerLevel} (which
 * registers a capability listener) and then calls {@code CableUtils.emit} on
 * it. That is the same hazard class as the ejector, and nothing deferred it.
 * An audit of all 327 descendants across the installed Mekanism jars found
 * nine such classes outside the defer's reach.
 *
 * <p>So the rule is now: <b>a tick may be offloaded only when the shape the
 * defer actually covers appears in its chain</b> — {@link #DEFERRED_SHAPE} for
 * the ejector-based machines, {@link #GENERATOR_SHAPE} for the generators
 * (with a readiness gate on the lazily built {@code outputCaches}), and
 * {@link #HEATER_SHAPES} for the two heat simulators (whose simulate-defer
 * mixin runs the whole exchange on the server thread). The chain must reach
 * {@link #MEKANISM_BASE}, every class in the chain must live in the
 * {@code mekanism.} package, and none may be on {@link #DENIED}. Everything
 * else runs on the server thread. That is 40 of the 327 installed classes:
 * the recipe machines, the factories, the chemical machines, the energy cube,
 * the chemical tank, the generators, and the two heaters — the CPU-heavy
 * population this addon exists for. The rest have trivial or empty ticks or
 * shared mutable state that cannot be made thread-safe by a per-call defer,
 * so refusing them costs nothing measurable and closes the hole.
 *
 * <p>The {@code mekanism.} package requirement is load-bearing. Mekanism addons
 * ({@code com.beipuo.mekenergistics}, {@code com.jerry.mekextras}, …) extend
 * these same base classes and live in their own packages; their ticks reach
 * third-party networks that are not thread-safe. AE2's {@code TickHandler} queue
 * is the concrete case: an off-thread tick from an AE2-backed machine drained it
 * concurrently with the server thread, and AE2's catch-and-retry loop turned
 * that into 62k logged NPEs and a minute-long stall.
 *
 * <p><b>Multiblocks are a second population with a second rule.</b> Their heavy
 * work is not in the block entity's tick but in the structure's shared
 * {@code MultiblockData}, which {@code TileEntityMultiblockDataTickMixin} steals
 * from the middle of {@code TileEntityMultiblock.onUpdateServer}. That call site
 * is not reached through {@link #mayOffload} — the data object is not a block
 * entity — so it carries its own gate, {@link #mayOffloadMultiblockData}, over
 * {@link #MULTIBLOCK_DATA}. That is a closed list of the six families a mixin
 * defers: a data class is on it only when every world-reaching call in its
 * {@code tick} is wrapped and handed back to the server thread. It is closed on
 * purpose — a seventh family from a Mekanism update, or one from an addon, would
 * otherwise be offloaded with its neighbour pushes running on the worker, which
 * is the same hazard class as an off-thread capability write.
 *
 * <p>The dynamic tank and the thermal evaporation plant are deliberately absent
 * from that list. Their ticks do read no neighbour, but nothing in this addon
 * defers anything for them either, so admitting them would mean trusting an
 * audit of two more tick bodies for two structures a base has one of. Refusing
 * them costs nothing measurable.
 */
public final class MekOffloadPolicy {

    /**
     * The shape {@code TileEntityConfigurableMachineEjectMixin} covers: every
     * {@code onUpdateServer} in this family reaches
     * {@code TileEntityConfigurableMachine.onUpdateServer}, whose
     * {@code ejectorComponent.tickServer()} call site is wrapped and deferred.
     *
     * <p>Requiring this in the chain is what makes the policy safe by
     * construction rather than by a list that has to keep up with Mekanism.
     */
    private static final String DEFERRED_SHAPE = "mekanism.common.tile.prefab.TileEntityConfigurableMachine";

    /**
     * The second shape the defer covers: {@code TileEntityGenerator}, the base
     * of every MekanismGenerators generator.
     *
     * <p>Its tick reaches the world in exactly two places, and both are in the
     * base class's {@code onUpdateServer} — the lazily built
     * {@code BlockEnergyCapabilityCache} list and the {@code CableUtils.emit}
     * that pushes into it. {@code TileEntityGeneratorEmitMixin} defers the emit,
     * and {@link #energyCachesReady} holds the tick on the server thread until
     * the caches exist, which is what keeps the capability-listener registration
     * off a worker.
     */
    private static final String GENERATOR_SHAPE = "mekanism.generators.common.tile.TileEntityGenerator";

    /**
     * The field {@link #GENERATOR_SHAPE} builds lazily and
     * {@code invalidateDirectionCaches} clears. Read reflectively: the policy has
     * no compile-time Mekanism dependency, so the type cannot be named.
     */
    private static final String GENERATOR_CACHE_FIELD = "outputCaches";

    /**
     * The two heat simulator tiles, whose tick is offloaded wholesale to the
     * compute pool: the only world reach is {@code ITileHeatHandler.simulate()},
     * which the per-tile mixin defers to the server thread under the tile lock.
     * No readiness gate is needed because the defer runs the entire heat
     * exchange (neighbour reads and writes included) on the server thread — the
     * worker side is pure energy bookkeeping.
     */
    private static final Set<String> HEATER_SHAPES = Set.of(
            "mekanism.common.tile.machine.TileEntityResistiveHeater",
            "mekanism.common.tile.machine.TileEntityFuelwoodHeater");

    /** Mekanism's own base class; a chain that reaches it is a Mekanism tile. */
    private static final String MEKANISM_BASE = "mekanism.common.tile.base.TileEntityMekanism";

    /** Every class in the chain must be Mekanism proper. */
    private static final String MEKANISM_PACKAGE = "mekanism.";

    /**
     * Audited exceptions <em>inside</em> the deferred shape — the only two
     * descendants of {@link #DEFERRED_SHAPE} whose tick reaches the world
     * anywhere other than the wrapped ejector call.
     *
     * <p>Every other hazardous family is excluded by the shape rule, not by a
     * name here. The families the audit read and rejected, with the call that
     * put them there:
     * <ul>
     *   <li><b>Generators</b> ({@code TileEntityGenerator} and every
     *       {@code MekanismGenerators} generator under it):
     *       {@code onUpdateServer} creates its
     *       {@code BlockEnergyCapabilityCache} over the {@code ServerLevel} and
     *       calls {@code CableUtils.emit} into the neighbours, in its own body.
     *       Deferring that would mean deferring the whole body, which would
     *       leave the worker nothing to do.</li>
*   <li><b>Heat simulators</b> (offloaded as of 0.3.9): the defer mixin
 *       runs the whole {@code simulate()} under the tile lock on the server
 *       thread. Listed here so a reader who finds the older revision's
 *       "heat simulators are denied" knows why it changed.</li>
     *   <li><b>Storage tiles</b> ({@code TileEntityBin},
     *       {@code TileEntityFluidTank}, {@code TileEntityRadioactiveWasteBarrel},
     *       {@code TileEntityLogisticalSorter}): same neighbour capability cache
     *       and emit.</li>
     *   <li><b>Miners and pumps</b> ({@code TileEntityDigitalMiner},
     *       {@code TileEntityElectricPump}): same, plus the miner ejects into two
     *       neighbour positions.</li>
     *   <li><b>Lasers</b> ({@code TileEntityBasicLaser} and its four
     *       subclasses): AABB entity scan, entity damage, block breaking, fake
     *       player.</li>
     *   <li><b>Multiblocks</b> ({@code TileEntityMultiblock} /
     *       {@code TileEntityInternalMultiblock} /
     *       {@code TileEntityStructuralMultiblock} and the casings / valves /
     *       ports / cells / coils / rotors / turbine vents under them): the
     *       <em>tile</em> tick is not offloaded here.
     *       {@code TileEntityMultiblock.onUpdateServer} runs the formation
     *       protocol, the shared {@code MultiblockManager} queues, the
     *       comparator notifications and the subclass neighbour updates
     *       around the simulation, and the blanket steal would take all of
     *       it. What is offloaded is the narrow
     *       {@code MultiblockData.tick(level)} call in the middle, by
     *       {@code TileEntityMultiblockDataTickMixin}, together with the
     *       per-family {@code Util.emit} pushes its partners defer back to the
     *       server thread.</li>
     *   <li><b>QIO</b> ({@code TileEntityQIOComponent} and every QIO part):
     *       drives the shared QIO network.</li>
     *   <li><b>Others</b>: {@code TileEntityTeleporter} (the teleport),
     *       {@code TileEntityChargepad} (entity scan), {@code
     *       TileEntityFluidicPlenisher} (places fluid blocks),
     *       {@code TileEntitySeismicVibrator} (sculk game event),
     *       {@code TileEntityDimensionalStabilizer} (chunk tickets),
     *       {@code TileEntityModificationStation} (edits stacks the player's open
     *       container owns), {@code TileEntitySecurityDesk} (reads the shared
     *       security frequency and stamps its mode onto a slot's item stack).</li>
     * </ul>
     */
    private static final Set<String> DENIED = Set.of(
            // InventoryFrequency.handleEject walks every other entangloporter in
            // the frequency and pushes into their neighbours, and its own body
            // also runs simulate() over neighbour heat handlers. It is a
            // configurable machine, so the shape rule would otherwise let it
            // through.
            "mekanism.common.tile.TileEntityQuantumEntangloporter",
            // moveItemsToGrid / organizeStock read the level through the formula
            // and recipe API and write the crafting grid.
            "mekanism.common.tile.machine.TileEntityFormulaicAssemblicator",
            // recalculateProductionRate reads WorldUtils.getSunBrightness in the
            // tick body and stores the result into productionRate, which the
            // recipe cache then consumes. A read that feeds arithmetic cannot be
            // deferred the way a push can — the tick would have to invent a
            // number — and this one is not reachable through the wrapped ejector
            // call, so the shape rule does not cover it.
            "mekanism.common.tile.machine.TileEntitySolarNeutronActivator",
            // The four generators whose own tick body reads the level. All three
            // reads return a value the tick feeds straight into its arithmetic,
            // so they cannot be deferred the way a push can — deferring
            // getBoost() would have to invent a number.
            //   TileEntityHeatGenerator.getBoost   -> WorldUtils.getFluidState
            //                                         x6 neighbours + ultraWarm
            //   TileEntitySolarGenerator.checkCanSeeSun -> SolarCheck ->
            //                                         WorldUtils.canSeeSun
            //   TileEntityWindGenerator.getMultiplier -> Level.getFluidState,
            //                                         canSeeSky, dimensionType
            "mekanism.generators.common.tile.TileEntityHeatGenerator",
            "mekanism.generators.common.tile.TileEntitySolarGenerator",
            // Inherits the refusal through TileEntitySolarGenerator; named so a
            // future reparenting cannot quietly let it back in.
            "mekanism.generators.common.tile.TileEntityAdvancedSolarGenerator",
            "mekanism.generators.common.tile.TileEntityWindGenerator"
    );

    /** Per-class verdict cache: the check runs for every BE every tick. */
    private static final ConcurrentHashMap<Class<?>, Verdict> CACHE = new ConcurrentHashMap<>();

    /**
     * What {@link #mayOffload} needs to know about one block-entity class,
     * resolved once and cached.
     *
     * @param offloadable whether the class passes the chain rule
     * @param readinessField for a generator, the field that must be non-null
     *        before its tick may move off the server thread; {@code null} for
     *        every other shape, which has no such precondition
     */
    private record Verdict(boolean offloadable, Field readinessField) {

        static final Verdict REFUSED = new Verdict(false, null);
        static final Verdict ALLOWED = new Verdict(true, null);
    }

    /**
     * The {@code MultiblockData} classes whose {@code tick} may run on a worker,
     * one entry per per-family defer mixin.
     *
     * <p>Membership means: every call in that family's {@code tick} that reads or
     * writes anything outside the data object is wrapped by a mixin and routed
     * through {@code MekDeferral}. The lists below are the audit result, per
     * family — the calls that had to be wrapped, and what they touch.
     *
     * <ul>
     *   <li>{@code BoilerMultiblockData}: two {@code ChemicalUtil.emit} pushes
     *       into the boiler valves, and the {@code static} {@code hotMap} every
     *       boiler shares.</li>
     *   <li>{@code MatrixMultiblockData}: {@code CableUtils.emit} into the
     *       induction ports, and {@code markDirtyComparator} which walks the
     *       valves through {@code WorldUtils.getTileEntity}.</li>
     *   <li>{@code SPSMultiblockData}: {@code ChemicalUtil.emit} and
     *       {@code kill}, which scans a bounding box and strikes entities.</li>
     *   <li>{@code TurbineMultiblockData}: {@code FluidUtils.emit} paired with
     *       the drain that consumes its return value, and {@code CableUtils.emit}.
     *       </li>
     *   <li>{@code FissionReactorMultiblockData}: two
     *       {@code ChemicalUtil.emit} calls, {@code radiate} inside
     *       {@code burnFuel}, and {@code handleDamage} / {@code radiateEntities}.
     *       </li>
     *   <li>{@code FusionReactorMultiblockData}: {@code CableUtils.emit},
     *       {@code ChemicalUtil.emit} and {@code kill}.</li>
     * </ul>
     */
    private static final Set<String> MULTIBLOCK_DATA = Set.of(
            "mekanism.common.content.boiler.BoilerMultiblockData",
            "mekanism.common.content.matrix.MatrixMultiblockData",
            "mekanism.common.content.sps.SPSMultiblockData",
            "mekanism.generators.common.content.turbine.TurbineMultiblockData",
            "mekanism.generators.common.content.fission.FissionReactorMultiblockData",
            "mekanism.generators.common.content.fusion.FusionReactorMultiblockData"
    );

    /** Per-class verdict cache for the data-object rule. */
    private static final ConcurrentHashMap<Class<?>, Boolean> DATA_CACHE = new ConcurrentHashMap<>();

    private MekOffloadPolicy() {
    }

    /** True when this block entity's tick may run on a compute thread. */
    public static boolean mayOffload(BlockEntity be) {
        if (be == null || DISABLED) {
            return false;
        }
        Verdict verdict = CACHE.computeIfAbsent(be.getClass(), MekOffloadPolicy::verdict);
        if (!verdict.offloadable()) {
            return false;
        }
        Field gate = verdict.readinessField();
        return gate == null || cachesReady(be, gate);
    }

    /**
     * The one precondition a generator tick carries: {@code outputCaches} must
     * already exist.
     *
     * <p>{@code TileEntityGenerator.onUpdateServer} builds that list lazily, and
     * building it calls
     * {@code BlockEnergyCapabilityCache.create(ServerLevel, …)} — which
     * registers a capability listener and so mutates the level's
     * {@code byChunkThenBlock} map, a plain {@code Long2ReferenceOpenHashMap}.
     * That is the same hazard class as the ejector's neighbour push, and unlike
     * the push it cannot be deferred: the very next statement reads the list.
     *
     * <p>So the one tick that builds it stays on the server thread, and every
     * tick after it is free to move. The cost is one reflective field read per
     * generator per tick, resolved once per class.
     *
     * <p>An unfuelled generator never builds the list — the build sits behind
     * {@code canFunction()} — so it never offloads. That is the right way round:
     * its tick is a no-op anyway, and the moment it has fuel the list is built
     * on the server thread and it starts moving.
     */
    private static boolean cachesReady(BlockEntity be, Field gate) {
        try {
            return gate.get(be) != null;
        } catch (ReflectiveOperationException e) {
            // Fail closed: a generator we cannot inspect keeps vanilla semantics.
            return false;
        }
    }

    /**
     * True when this structure's shared simulation may run on a compute thread.
     *
     * <p>Exact class match rather than a superclass walk: the mixins target the
     * concrete data classes, so a subclass — which is what an addon would write —
     * inherits none of their wrapping and must stay on the server thread.
     */
    public static boolean mayOffloadMultiblockData(Object data) {
        if (data == null || DISABLED) {
            return false;
        }
        return DATA_CACHE.computeIfAbsent(data.getClass(), type -> MULTIBLOCK_DATA.contains(type.getName()));
    }

    /**
     * Global kill switch. Set {@code -Dthreadtearer.mek.offload=false} to make
     * every Mekanism tick run on the server thread, which is the fastest way to
     * tell whether a freeze involves this addon at all.
     */
    private static final boolean DISABLED =
            "false".equalsIgnoreCase(System.getProperty("threadtearer.mek.offload", "true"));

    /** True when the kill switch is active (for the startup log line). */
    public static boolean disabled() {
        return DISABLED;
    }

    /** Walks the superclass chain once and produces the cached verdict. */
    private static Verdict verdict(Class<?> type) {
        List<String> chain = new ArrayList<>(8);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(c.getName());
        }
        if (!chainAllowed(chain)) {
            return Verdict.REFUSED;
        }
        if (!chain.contains(GENERATOR_SHAPE)) {
            return Verdict.ALLOWED;
        }
        Field gate = findField(type, GENERATOR_CACHE_FIELD);
        return gate == null ? Verdict.REFUSED : new Verdict(true, gate);
    }

    /**
     * Resolves a field declared anywhere in the chain and makes it accessible.
     * {@code null} when the chain does not declare it, which makes the caller
     * refuse rather than assume.
     */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field found = c.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                // The field belongs to the generator base class; keep walking.
            }
        }
        return null;
    }

    /**
     * The rule itself, as a pure function of a superclass chain ordered from the
     * concrete class upwards. Split out so the policy can be pinned by a test
     * without a Mekanism classpath.
     *
     * <p>Ways to fail, all of them fail closed: the chain leaves the
     * {@code mekanism.} package (an addon), it names a class on
     * {@link #DENIED}, it contains neither of the two covered shapes (no mixin
     * would hand its neighbour writes back to the server thread), or it never
     * reaches {@link #MEKANISM_BASE} (a shape this addon has never seen).
     *
     * <p>There are two covered shapes because there are two defer mixins:
     * {@link #DEFERRED_SHAPE} for the recipe machines and
     * {@link #GENERATOR_SHAPE} for MekanismGenerators. A chain may contain
     * either, and the generator family contains only the latter — generators do
     * not extend {@code TileEntityConfigurableMachine}.
     *
     * <p>The walk stops the moment the chain hits {@link #MEKANISM_BASE}.
     * Beyond it lies {@code CapabilityTileEntity} /
     * {@code net.minecraft.world.level.block.entity.BlockEntity}, neither of
     * which is in the {@code mekanism.} package and both of which are the
     * <em>expected</em> rest of every chain. Continuing past the base would
     * fail every verdict, exactly the regression the diagnostic caught in 0.3.1.
     */
    static boolean chainAllowed(List<String> superChain) {
        boolean covered = false;
        for (String name : superChain) {
            if (name == null || !name.startsWith(MEKANISM_PACKAGE) || DENIED.contains(name)) {
                return false;
            }
            covered |= DEFERRED_SHAPE.equals(name)
                    || GENERATOR_SHAPE.equals(name)
                    || HEATER_SHAPES.contains(name);
            if (MEKANISM_BASE.equals(name)) {
                return covered;
            }
        }
        return false;
    }

    /** Size of the audited deny list, exposed for the startup log line. */
    public static int deniedSize() {
        return DENIED.size();
    }

    /** The audited deny list, exposed so a test can pin it. */
    static Set<String> deniedClasses() {
        return DENIED;
    }

    /** The shape the policy requires, exposed so a test can pin it. */
    static String deferredShape() {
        return DEFERRED_SHAPE;
    }

    /** The second covered shape, exposed so a test can pin it. */
    static String generatorShape() {
        return GENERATOR_SHAPE;
    }

    /** The data classes the multiblock rule covers, exposed so a test can pin it. */
    static Set<String> multiblockDataClasses() {
        return MULTIBLOCK_DATA;
    }
}
