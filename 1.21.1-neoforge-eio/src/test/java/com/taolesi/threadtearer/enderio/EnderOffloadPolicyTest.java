package com.taolesi.threadtearer.enderio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the offload allowlist for EnderIO.
 *
 * <p>Unlike the mek addon's single-base-class rule ({@code TileEntityConfigurableMachine}
 * separating safe machines from unsafe), EnderIO's per-machine difference lives in
 * each subclass's {@code craftingTaskHost.tick()} body — all five admitted machines
 * share the same parent chain through {@code MachineBlockEntity} → {@code EIOBlockEntity}
 * → {@code EnderBlockEntity} (EnderCore), so the test pins that exact chain. A chain
 * invented from memory passes the same assertions while describing a class that does
 * not exist — the mistake the mek policy test was rewritten to prevent.
 *
 * <p>The class-name allowlist alone wouldn't have caught an EnderIO class typo: an
 * entry like {@code com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntit}
 * (missing the trailing 'y') would pass the leaf check while naming a class that
 * never loads. The chain walk fixes that by requiring the real
 * {@code EnderBlockEntity} base, which only appears when every link in the chain
 * exists.
 */
class EnderOffloadPolicyTest {

    /** The real chain suffix of every admitted EnderIO machine, read from the source. */
    private static final String ENDERIO_MACHINE = "com.enderio.enderio.foundation.block.entity.MachineBlockEntity";
    private static final String EIO_BE = "com.enderio.enderio.foundation.block.EIOBlockEntity";
    private static final String ENDER_BASE = "com.enderio.core.common.blockentity.EnderBlockEntity";

    /** The allowlist is exactly the machines whose tick body was read and found pure. */
    @Test
    void allowListIsExactlyTheAuditedMachines() {
        assertEquals(Set.of(
                "com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntity",
                "com.enderio.enderio.content.machines.sag_mill.SagMillBlockEntity",
                "com.enderio.enderio.content.machines.slicer.SlicerBlockEntity",
                "com.enderio.enderio.content.machines.vat.VatBlockEntity",
                "com.enderio.enderio.content.machines.soul_binder.SoulBinderBlockEntity"),
                EnderOffloadPolicy.allowedClasses());
        assertEquals(5, EnderOffloadPolicy.allowedSize());
    }

    /** The admitted machines, each through its real chain. */
    @Test
    void admittedMachinesAreOffloadedThroughTheirRealChains() {
        // PoweredMachineBlockEntity sits between MachineBlockEntity and the
        // concrete class for the four powered machines (Alloy Smelter, SAG Mill,
        // Slicer, Soul Binder); Vat extends MachineBlockEntity directly. Both
        // shapes must walk through to EnderBlockEntity.
        for (String leaf : EnderOffloadPolicy.allowedClasses()) {
            if (leaf.endsWith("VatBlockEntity")) {
                assertAllowed(leaf, ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
            } else {
                assertAllowed(leaf,
                        "com.enderio.enderio.foundation.block.entity.PoweredMachineBlockEntity",
                        ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
            }
        }
    }

    /**
     * Refusals, with the reason the source gave. CapacitorBank, VacuumChest,
     * FarmingStation, PoweredSpawner and the Obelisks read the world (entity
     * scans, block placement, fake-player interaction) and cannot use the
     * mek-style split. Their leaves are not in {@code ALLOWED}.
     */
    @Test
    void worldReadingMachinesAreRefused() {
        assertRefused("com.enderio.enderio.content.machines.capacitor_bank.CapacitorBankBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        assertRefused("com.enderio.enderio.content.machines.vacuum.chest.VacuumChestBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        assertRefused("com.enderio.enderio.content.machines.vacuum.xp.XPVacuumBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        assertRefused("com.enderio.enderio.content.machines.farming_station.FarmingStationBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        assertRefused("com.enderio.enderio.content.machines.powered_spawner.PoweredSpawnerBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        assertRefused("com.enderio.enderio.content.machines.obelisks.attractor.AttractorObeliskBlockEntity",
                "com.enderio.enderio.content.machines.obelisks.ObeliskBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
    }

    /**
     * An addon machine extending an admitted EnderIO class inherits none of this
     * addon's guarantees and must fail closed on the leaf check.
     */
    @Test
    void addonMachinesExtendingAdmittedClassesAreRefused() {
        assertRefused("com.example.somemod.tile.CustomAlloySmelterBlockEntity",
                "com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntity",
                "com.enderio.enderio.foundation.block.entity.PoweredMachineBlockEntity",
                ENDERIO_MACHINE, EIO_BE, ENDER_BASE);
        // A chain that leaves both known packages is not a licence either.
        assertFalse(EnderOffloadPolicy.chainAllowed(List.of(
                "com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntity",
                "com.enderio.enderio.foundation.block.entity.PoweredMachineBlockEntity",
                "com.example.somemod.tile.SomeBase")));
        // A chain that never reaches the EnderCore base is not either.
        assertFalse(EnderOffloadPolicy.chainAllowed(List.of(
                "com.enderio.enderio.content.machines.alloy.AlloySmelterBlockEntity",
                "com.enderio.enderio.foundation.block.entity.PoweredMachineBlockEntity",
                ENDERIO_MACHINE)));
        // Nor is an empty one.
        assertFalse(EnderOffloadPolicy.chainAllowed(List.of()));
    }

    private static void assertAllowed(String... chain) {
        assertTrue(EnderOffloadPolicy.chainAllowed(List.of(chain)),
                "expected offloadable: " + chain[0]);
    }

    private static void assertRefused(String... chain) {
        assertFalse(EnderOffloadPolicy.chainAllowed(List.of(chain)),
                "expected denied: " + chain[0]);
    }
}