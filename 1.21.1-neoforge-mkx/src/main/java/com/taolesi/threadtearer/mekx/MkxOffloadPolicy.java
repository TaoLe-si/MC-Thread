package com.taolesi.threadtearer.mekx;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Mekanism Extras factory block-entity ticks may run on a compute worker.
 *
 * <p>Read against the real source: Mekanism Extras branch {@code 1.21.1}
 * (jar 1.21.1-1.3.0). Every one of the sixteen factory BEs listed in
 * {@link #ALLOWED} is a {@code TileEntityConfigurableMachine} — directly or
 * through {@code TileEntityExtraAdvancedBase} / {@code TileEntityExtraMoreMachineFactory}
 * — and its {@code onUpdateServer} is a pure own-state body: vanilla
 * RecipeManager lookup, self-slot mutation, self-energy container mutation,
 * {@code TileComponentEjector.tickServer()} for the neighbour push. The
 * ejector half is the same neighbour-IO shape the mek addon's
 * {@code TileEntityConfigurableMachineEjectMixin} already defers; this addon
 * writes no ejector mixin of its own and reuses that one.
 *
 * <p><b>Why a separate addon, not a rule inside the mek addon.</b> The mek
 * addon's {@code MekOffloadPolicy} requires every class in the superclass
 * chain to live in the {@code mekanism.} package; Mekanism Extras factories
 * live in {@code com.jerry.mekextras.*}, so the mek addon denies them. A
 * separate addon with its own narrow allowlist is the right scope: the shape
 * matches mek's configurable machines, but the policy belongs here, not
 * inside the mek addon's verified rule. Both addons compose cleanly via
 * MixinExtras' nested {@code @WrapOperation} chain — see
 * {@code TileEntityMekanismTickMixin} for the chain order and the reason the
 * mek addon's chain still resolves these BEs to {@code false} on the way
 * through.
 *
 * <p><b>What was read, per family:</b>
 * <ul>
 *   <li><b>Admitted — eleven mekaf factories.</b> Every {@code onUpdateServer}
 *       in this family calls {@code super.onUpdateServer()} (the mek
 *       {@code TileEntityConfigurableMachine.onUpdateServer}), then runs
 *       energy slot fill, secondary fuel handling, inventory sort, and the
 *       recipe cache update per slot. None read the world, none touch a
 *       neighbour capability directly.</li>
 *   <li><b>Admitted — five mekmm factories.</b> Same shape through
 *       {@code TileEntityExtraMoreMachineFactory.onUpdateServer}: pure own-state
 *       recipe + slot + energy, the ejector push is the only neighbour reach
 *       and is handled by the mek addon's ejector mixin.</li>
 * </ul>
 *
 * <p><b>Refused</b> — none of the non-factory Mekanism Extras BEs are on the
 * allowlist. The transmitters ({@code TileEntityExtraUniversalCable} and
 * kin) reach into neighbour capability handlers and so cannot move; the
 * reinforced matrix energy container reaches into {@code TileComponentEjector}
 * via a path the audit did not cover. Adding them later is a one-line edit
 * to {@link #ALLOWED} after the same audit as the mek addon's
 * {@code TileEntityGenerator} received.
 */
public final class MkxOffloadPolicy {

    /**
     * The closed allowlist. Each entry's {@code onUpdateServer} was read and
     * found to consist of {@code super.onUpdateServer()} (mek recipe + ejector
     * + sendData) plus a small own-state extension (energy slot, secondary
     * fuel, inventory sort, recipe cache lookup). See the class javadoc for the
     * per-family verdicts and the mek addon's policy as the load-bearing
     * constraint this addon sits next to.
     */
    private static final Set<String> ALLOWED = Set.of(
            // mekaf: eleven advanced factories
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
            // mekmm: five more-machine factories
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraItemStackToItemStackMoreMachineFactory",
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraItemToItemMoreMachineFactory",
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraPlantingFactory",
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraRecyclingFactory",
            "com.jerry.mekextras.common.integration.mekmm.tile.factory.TileEntityExtraReplicatingFactory"
    );

    /**
     * The only Mekanism-side class a chain passes through: every admitted
     * factory reaches {@code TileEntityConfigurableMachine} (directly or via
     * the two abstract bases). The chain must end there to be a
     * {@code TileEntityConfigurableMachine} subclass at all — the same shape
     * the mek addon's defer mixin covers.
     */
    private static final String MEK_BASE = "mekanism.common.tile.prefab.TileEntityConfigurableMachine";

    /**
     * The two chains that a real factory passes through: the {@code mekextras.}
     * side (the factory itself, the two abstract bases {@code TileEntityExtraAdvancedBase}
     * and {@code TileEntityExtraMoreMachineFactory}) and the {@code mekanism.}
     * side (the shared {@code TileEntityConfigurableMachine} /
     * {@code TileEntityElectricMachine} / {@code TileEntityMekanism} chain).
     *
     * <p>An addon machine extending an admitted factory inherits none of this
     * addon's guarantees; failing closed on the leaf check catches that.
     */
    private static final String MKX_PACKAGE = "com.jerry.mekextras.";
    private static final String MEK_PACKAGE = "mekanism.";

    /** Per-class verdict cache: the check runs for every BE every tick. */
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private MkxOffloadPolicy() {
    }

    /** True when this block entity's tick may run on a compute worker. */
    public static boolean mayOffload(BlockEntity be) {
        if (be == null || DISABLED) {
            return false;
        }
        return CACHE.computeIfAbsent(be.getClass(), MkxOffloadPolicy::verdict);
    }

    /**
     * The rule itself, as a pure function of a superclass chain ordered from
     * the concrete class upwards. Split out so the policy can be pinned by a
     * test without a Mekanism / Mekanism Extras classpath, exactly as the mek
     * and IF addons' {@code chainAllowed} is.
     *
     * <p>The leaf must be on {@link #ALLOWED}; from there the walk must reach
     * {@link #MEK_BASE} without ever leaving the {@code mekextras.} or
     * {@code mekanism.} packages. An addon machine extending an admitted class
     * fails on the leaf — its own name is not on the list.
     */
    static boolean chainAllowed(List<String> superChain) {
        if (superChain.isEmpty() || !ALLOWED.contains(superChain.get(0))) {
            return false;
        }
        for (int i = 1; i < superChain.size(); i++) {
            String name = superChain.get(i);
            if (MEK_BASE.equals(name)) {
                return true;
            }
            if (name == null
                    || !(name.startsWith(MKX_PACKAGE) || name.startsWith(MEK_PACKAGE))) {
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
            if (MEK_BASE.equals(name)) {
                return true;
            }
            if (!(name.startsWith(MKX_PACKAGE) || name.startsWith(MEK_PACKAGE))) {
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
     * Global kill switch. Set {@code -Dthreadtearer.mkx.offload=false} to
     * make every Mekanism Extras tick run on the server thread.
     */
    private static final boolean DISABLED =
            "false".equalsIgnoreCase(System.getProperty("threadtearer.mkx.offload", "true"));

    /** True when the kill switch is active (for the startup log line). */
    public static boolean disabled() {
        return DISABLED;
    }
}