package com.taolesi.threadtearer.ie;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Immersive Engineering block-entity ticks may run on a compute worker.
 *
 * <p>Read against the real source: Immersive Engineering branch
 * {@code 1.21.1} (jar 12.4.3). IE's tick architecture is more heterogeneous
 * than Mekanism / IF / EIO / Mekanism Extras: each BE does its own thing
 * rather than fitting a single shape. Twenty-nine BEs implement
 * {@code IEServerTickableBE} and reach the same {@code tickServer()}
 * call site through one chokepoint
 * ({@code IEServerTickableBE.makeTicker()}), but their tick bodies vary
 * wildly — kinetic transmission, neighbour-fluid push, entity attack,
 * fake-light placement, comparator output, multiblock sync. Of those
 * twenty-nine, only three have a tickServer body that is purely own-state
 * or has only the world calls the core's {@code LevelMixin} already
 * forwards automatically — {@code level.updateNeighborsAt},
 * {@code level.updateNeighbourForOutputSignal},
 * {@code level.setBlockAndUpdate}, {@code level.removeBlock}.
 *
 * <p><b>Admitted — three machines.</b>
 * <ul>
 *   <li><b>ChargingStationBlockEntity</b> (充电站): reads
 *       {@code EnergyStorage.ITEM} from the BE's own inventory slot
 *       (an item capability, not a world capability), mutates the BE's
 *       own energy container, and calls
 *       {@code level.updateNeighborsAt} once every 32 ticks — already
 *       forwarded by the core's {@code LevelMixin}. Pure own-state plus
 *       a single redstone update the core handles.</li>
 *   <li><b>SampleDrillBlockEntity</b> (样本钻): the cache for
 *       {@code ExcavatorHandler.getMineralWorldInfo} is
 *       {@code synchronized}-guarded and read-only; the heavy work
 *       happens inside the {@code synchronized} block; the read returns a
 *       snapshot. {@code level.updateNeighbourForOutputSignal} and the
 *       chunk-dirty / block-update calls are all auto-forwarded by the
 *       core.</li>
 *   <li><b>ClocheBlockEntity</b> (温室): recipe matching and self-state
 *       inventory mutation, with one neighbour push per tick via
 *       {@code net.neoforged.neoforge.items.ItemHandlerHelper.insertItem}
 *       on a neighbour {@code IItemHandler}. The ejector mixin wraps
 *       that one static call site and defers it to the server thread via
 *       {@code deferWorldWrite} — the FIFO drain is sequential, so the
 *       neighbour {@code IItemHandler.insertItem} is reached from at
 *       most one thread at a time, and no per-BE lock is needed. The
 *       worker still does the heavy recipe + inventory work in parallel;
 *       the ejector is the only part that runs server-thread.</li>
 * </ul>
 *
 * <p><b>Refused — twenty-six machines.</b> The refusals fall into five
 * shape classes, recorded here so a future maintainer who wants to add
 * one of them knows which call site they're auditing:
 * <ul>
 *   <li><b>Fluid / item / energy transport (8)</b>:
 *       {@code WoodenBarrel}, {@code FluidPump}, {@code FluidPlacer},
 *       {@code ConveyorBelt}, {@code ItemBatcher},
 *       {@code ThermoelectricGen}, {@code EnergyConnector},
 *       {@code Capacitor}. Each calls
 *       {@code IEBlockCapabilityCache<I*Handler>.getCapability().fill/insert/extract/extract}
 *       on a <em>neighbour's</em> capability handler. The core's
 *       {@code LevelMixin} does not intercept these — it intercepts
 *       {@code Level}-level writes only. Same shape as SFM / Pipez:
 *       {@code IItemHandler.insertItem} etc. must run on the server
 *       thread because the underlying vanilla containers are not
 *       thread-safe.</li>
 *   <li><b>Kinetic transmission (3)</b>: {@code Windmill},
 *       {@code Watermill}, {@code ConveyorBelt} (rotation half). Each
 *       pushes rotation to a neighbour {@code IRotationAcceptor}.
 *       IE's rotation network is shared across machines the same way
 *       Create's {@code KineticNetwork} is — concurrent worker dispatch
 *       would race on the network's membership map. Same hazard class
 *       as the kinetic shape analysis in the mek and IF addons.</li>
 *   <li><b>Entity interaction (4)</b>: {@code Turret},
 *       {@code TeslaCoil}, {@code Electromagnet}, {@code Siren}. Each
 *       reads or mutates the level's entity list — vanilla's
 *       {@code Level.getEntitiesOfClass} and {@code Entity.hurt} are
 *       not thread-safe.</li>
 *   <li><b>Fake light / redstone (5)</b>: {@code Floodlight},
 *       {@code ElectricLantern}, {@code FakeLight},
 *       {@code RedstoneBreaker}, {@code RedstoneSwitchboard},
 *       {@code ConnectorRedstone}, {@code ConnectorBundled}. The
 *       Floodlight's tickServer places and removes fake light blocks via
 *       {@code level.setBlockAndUpdate}, which the core's
 *       {@code LevelMixin} already forwards — but every tick, on every
 *       floodlight, with no perceivable CPU saving. The redstone
 *       connectors forward comparator signals that already route
 *       through the core's neighbour-changed mixin.</li>
 *   <li><b>Multiblocks and bookkeeping (6)</b>: {@code ChunkLoaderLogic}
 *       force-loads chunks, {@code StripCurtain} tracks state, the
 *       energy / fluid meters are display-only. These have nothing to
 *       compute and nothing to offload.</li>
 * </ul>
 *
 * <p><b>Why three machines, not twenty-nine.</b> The offloadable shape
 * requires the per-tick work to be pure own-state plus world calls the
 * core already intercepts, or a single ejector that can be deferred via
 * {@code deferWorldWrite}. Most IE BEs were designed around a
 * neighbour-capability ecosystem the core does not know about, so
 * offloading them would require extending the core's interaction thread
 * to cover {@code IEBlockCapabilityCache}, which is a core-mod change
 * — out of scope for an addon. Three is the whole subset where this
 * addon's shape fits.
 */
public final class IeOffloadPolicy {

    /**
     * The closed allowlist. Each entry's {@code tickServer} body was read
     * and verified to consist of pure own-state work plus only the world
     * calls the core's {@code LevelMixin} already forwards. See the class
     * javadoc for the per-machine verdict and the refusal reasons for
     * everything not here.
     */
    private static final Set<String> ALLOWED = Set.of(
            "blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity",
            "blusunrize.immersiveengineering.common.blocks.metal.SampleDrillBlockEntity",
            "blusunrize.immersiveengineering.common.blocks.metal.ClocheBlockEntity"
    );

    /**
     * The single IE-side base every admitted chain walks up to:
     * {@code IEBaseBlockEntity}. Reached through {@code ImmersiveConnectableBlockEntity}
     * for the fluid pipes and directly for the metal machines.
     */
    private static final String IE_BASE =
            "blusunrize.immersiveengineering.common.blocks.IEBaseBlockEntity";

    /**
     * The only IE package a real chain may pass through: every admitted
     * machine lives under {@code blusunrize.immersiveengineering.common.blocks.metal}
     * (or {@code .wooden}, etc., still inside {@code blusunrize.immersiveengineering.}).
     * The chain must end at {@code IEBaseBlockEntity} to be an IE BE at
     * all — IE ships its own non-{@code IEBaseBlockEntity} BEs (signs,
     * banners, balloons) which fall outside this addon's scope.
     */
    private static final String IE_PACKAGE = "blusunrize.immersiveengineering.";
    private static final String MINECRAFT_BASE =
            "net.minecraft.world.level.block.entity.BlockEntity";

    /** Per-class verdict cache: the check runs for every BE every tick. */
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private IeOffloadPolicy() {
    }

    /** True when this block entity's tick may run on a compute worker. */
    public static boolean mayOffload(BlockEntity be) {
        if (be == null || DISABLED) {
            return false;
        }
        return CACHE.computeIfAbsent(be.getClass(), IeOffloadPolicy::verdict);
    }

    /**
     * The rule itself, as a pure function of a superclass chain ordered from
     * the concrete class upwards. Split out so the policy can be pinned by a
     * test without an IE classpath, exactly as the mek and IF addons'
     * {@code chainAllowed} is.
     *
     * <p>The leaf must be on {@link #ALLOWED}; from there the walk must reach
     * one of the two base classes (IE's own {@link #IE_BASE} or, for any future
     * machine that goes through vanilla's {@link #MINECRAFT_BASE} directly) without
     * ever leaving the {@code blusunrize.immersiveengineering.} package. An addon
     * machine extending an admitted class fails on the leaf — its own name is not
     * on the list.
     */
    static boolean chainAllowed(List<String> superChain) {
        if (superChain.isEmpty() || !ALLOWED.contains(superChain.get(0))) {
            return false;
        }
        for (int i = 1; i < superChain.size(); i++) {
            String name = superChain.get(i);
            if (IE_BASE.equals(name) || MINECRAFT_BASE.equals(name)) {
                return true;
            }
            if (name == null || !name.startsWith(IE_PACKAGE)) {
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
            if (IE_BASE.equals(name) || MINECRAFT_BASE.equals(name)) {
                return true;
            }
            if (!name.startsWith(IE_PACKAGE)) {
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
     * Global kill switch. Set {@code -Dthreadtearer.ie.offload=false} to
     * make every IE tick run on the server thread.
     */
    private static final boolean DISABLED =
            "false".equalsIgnoreCase(System.getProperty("threadtearer.ie.offload", "true"));

    /** True when the kill switch is active (for the startup log line). */
    public static boolean disabled() {
        return DISABLED;
    }
}