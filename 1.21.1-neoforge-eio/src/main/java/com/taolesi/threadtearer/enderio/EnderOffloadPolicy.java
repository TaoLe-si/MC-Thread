package com.taolesi.threadtearer.enderio;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which EnderIO machine block-entity ticks may run on a compute worker.
 *
 * <p>Read against the real source: EnderIO branch {@code 1.21.1} (9.x alphas)
 * and its framework EnderCore. Every EnderIO machine is a {@code MachineBlockEntity}
 * (or its {@code PoweredMachineBlockEntity} subclass), which inherits the
 * single chokepoint {@code EnderBlockEntity.tick} from EnderCore. Wrapping that
 * one call site intercepts every EnderIO BE without needing a mixin per machine
 * class. The policy gates which subclass is allowed, so non-crafting machines
 * (VacuumChest, FarmingStation, PoweredSpawner, Obelisks, etc.) stay on the
 * server thread.
 *
 * <p><b>What was read, per family:</b>
 * <ul>
 *   <li><b>Admitted — crafting machines.</b> Every allowed machine carries
 *       a {@code CraftingMachineTaskHost} whose {@code tick()} is pure
 *       own-state: it runs {@code recipe.craft(...)} / {@code recipe.matches(...)}
 *       (vanilla RecipeManager lookups, no world reads), calls
 *       {@code energyStorage.consumeEnergy(...)} (its own {@code PoweredMachineEnergyStorage}),
 *       and {@code outputAccess.insertItem(inventory, item, false)} where
 *       {@code inventory} is the BE-local {@code MachineInventory} (an in-memory
 *       {@code NonNullList<ItemStack>}). The simulation-then-commit pattern
 *       {@code placeOutputs(outputs, false)} already snapshots the inventory
 *       before mutating, so the only side effect is on the BE's own slots and
 *       energy. The neighbour-IO half — {@code distributeResources(Direction)}
 *       → {@code TransferUtil.distributeItems/Fluids} → {@code IItemHandler.extract/insertItem}
 *       across positions — is split off by
 *       {@code MachineBlockEntityDistributeResourcesMixin} and forwarded back
 *       to the server thread, exactly as mek's ejector split is split off
 *       from mek's configurable-machine {@code onUpdateServer}.</li>
 * </ul>
 *
 * <p><b>Refused — every other EnderIO BE</b>, with the reason the source gave:
 * <ul>
 *   <li><b>CapacitorBank, StirlingGenerator, SolarPanel</b> — no crafting pattern;
 *       separate tick body. Not this release.</li>
 *   <li><b>VacuumChest, XPVacuum, FarmingStation, Drain</b> — entity or block
 *       scans of the surrounding world, or world-position reads whose value
 *       feeds the tick.</li>
 *   <li><b>PoweredSpawner, MindKiller, ImpulseHopper, PaintingMachine, Obelisks
 *       (Attractor/Aversion/Inhibitor/Relocator/Weather/XP)</b> — entity
 *       interaction or world writes ({@code level.setBlockAndUpdate} cascades)
 *       on the server thread.</li>
 * </ul>
 *
 * <p>An addon machine extending an admitted EnderIO class inherits none of this
 * addon's guarantees and fails closed on the leaf check; a chain that leaves
 * the EnderIO or EnderCore packages is not an EnderIO machine at all.
 */
public final class EnderOffloadPolicy {

    /**
     * The closed allowlist. Each entry's {@code serverTick} chain was read and
     * found to consist of {@code super.serverTick()} (own-state sync) →
     * {@code distributeResources()} (split off below) → {@code craftingTaskHost.tick()}
     * (pure own-state). See the class javadoc for the per-family verdicts and
     * the refusal reasons for everything not here.
     */
    private static final Set<String> ALLOWED = Set.of(
            "com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntity",
            "com.enderio.enderio.content.machines.sag_mill.SagMillBlockEntity",
            "com.enderio.enderio.content.machines.slicer.SlicerBlockEntity",
            "com.enderio.enderio.content.machines.vat.VatBlockEntity",
            "com.enderio.enderio.content.machines.soul_binder.SoulBinderBlockEntity"
    );

    /**
     * The only two packages a real EnderIO machine chain passes through.
     * EnderIO proper ({@code com.enderio.enderio.}) holds MachineBlockEntity
     * and its subclasses; EnderCore ({@code com.enderio.core.}) holds the
     * base EnderBlockEntity. The chain must reach EnderBlockEntity to be an
     * EnderIO machine at all.
     */
    private static final String ENDERIO_PACKAGE = "com.enderio.enderio.";
    private static final String ENDERCORE_PACKAGE = "com.enderio.core.";
    private static final String ENDER_BASE = "com.enderio.core.common.blockentity.EnderBlockEntity";

    /** Per-class verdict cache: the check runs for every BE every tick. */
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private EnderOffloadPolicy() {
    }

    /** True when this block entity's tick may run on a compute worker. */
    public static boolean mayOffload(BlockEntity be) {
        if (be == null || DISABLED) {
            return false;
        }
        return CACHE.computeIfAbsent(be.getClass(), EnderOffloadPolicy::verdict);
    }

    /**
     * The rule itself, as a pure function of a superclass chain ordered from
     * the concrete class upwards. Split out so the policy can be pinned by a
     * test without an EnderIO classpath, exactly as the mek and IF addons'
     * {@code chainAllowed} is.
     *
     * <p>The leaf must be on {@link #ALLOWED}; from there the walk must reach
     * {@code EnderBlockEntity} without ever leaving the EnderIO or EnderCore
     * packages. An addon machine extending an admitted class fails on the leaf
     * — its own name is not on the list — and a chain that stops short of the
     * EnderCore base is not an EnderIO machine either.
     */
    static boolean chainAllowed(List<String> superChain) {
        if (superChain.isEmpty() || !ALLOWED.contains(superChain.get(0))) {
            return false;
        }
        for (int i = 1; i < superChain.size(); i++) {
            String name = superChain.get(i);
            if (ENDER_BASE.equals(name)) {
                return true;
            }
            if (name == null
                    || !(name.startsWith(ENDERIO_PACKAGE) || name.startsWith(ENDERCORE_PACKAGE))) {
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
            if (ENDER_BASE.equals(name)) {
                return true;
            }
            if (!(name.startsWith(ENDERIO_PACKAGE) || name.startsWith(ENDERCORE_PACKAGE))) {
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
     * Global kill switch. Set {@code -Dthreadtearer.enderio.offload=false} to
     * make every EnderIO tick run on the server thread.
     */
    private static final boolean DISABLED =
            "false".equalsIgnoreCase(System.getProperty("threadtearer.enderio.offload", "true"));

    /** True when the kill switch is active (for the startup log line). */
    public static boolean disabled() {
        return DISABLED;
    }
}