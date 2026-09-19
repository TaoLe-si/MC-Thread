package com.taolesi.threadtearer.ie;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the offload allowlist for Immersive Engineering.
 *
 * <p>The closed allowlist is exactly three machines: {@code ChargingStation}
 * (own energy + item capability), {@code SampleDrill} (own recipe +
 * cached world read), {@code Cloche} (own recipe + neighbour IO via
 * ejector mixin). The chain walk requires every link to live in
 * {@code blusunrize.immersiveengineering.}; the chain must reach
 * {@code IEBaseBlockEntity} (or, for any future machine that goes through
 * vanilla's {@link #MINECRAFT_BASE} directly) to be an IE BE at all.
 */
class IeOffloadPolicyTest {

    /** The IE-side base every admitted chain must end at. */
    private static final String IE_BASE =
            "blusunrize.immersiveengineering.common.blocks.IEBaseBlockEntity";

    /** The intermediate base the metal machines walk through. */
    private static final String IMMERSIVE_CONNECTABLE =
            "blusunrize.immersiveengineering.common.blocks.generic.ImmersiveConnectableBlockEntity";

    /** The allowlist is exactly the three machines whose tick bodies fit the shape. */
    @Test
    void allowListIsExactlyTheAuditedMachines() {
        assertEquals(Set.of(
                "blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity",
                "blusunrize.immersiveengineering.common.blocks.metal.SampleDrillBlockEntity",
                "blusunrize.immersiveengineering.common.blocks.metal.ClocheBlockEntity"),
                IeOffloadPolicy.allowedClasses());
        assertEquals(3, IeOffloadPolicy.allowedSize());
    }

    /**
     * The admitted machines, each through its real chain. The three metal
     * machines sit below {@code ImmersiveConnectableBlockEntity} →
     * {@code IEBaseBlockEntity}; the connectable intermediate is the
     * common base that holds IE's neighbour-capability plumbing.
     */
    @Test
    void admittedMachinesAreOffloadedThroughTheirRealChains() {
        for (String leaf : IeOffloadPolicy.allowedClasses()) {
            assertAllowed(leaf, IMMERSIVE_CONNECTABLE, IE_BASE);
        }
    }

    /**
     * Refusals, with the reason the source gave. {@code FluidPump},
     * {@code FluidPlacer}, {@code WoodenBarrel}, {@code ConveyorBelt},
     * {@code ItemBatcher}, {@code ThermoelectricGen}, {@code EnergyConnector},
     * {@code Capacitor} all push into neighbour capability handlers that
     * the core does not intercept; {@code Turret}, {@code TeslaCoil},
     * {@code Electromagnet}, {@code Siren} read / mutate the level's
     * entity list. Their chain is the same shape — the audit just reads the
     * tickServer body and confirms the world-touching call.
     */
    @Test
    void worldTouchingMachinesAreRefused() {
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.FluidPumpBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.FluidPlacerBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.wooden.WoodenBarrelBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.ConveyorBeltBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.ThermoelectricGenBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.TurretBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        assertRefused("blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        // The base classes themselves are never admitted.
        assertRefused(IE_BASE);
        assertRefused(IMMERSIVE_CONNECTABLE);
    }

    /**
     * Addon machines extending an admitted IE class inherit none of this
     * addon's guarantees and must fail closed on the leaf check. A chain
     * that leaves the IE package, or stops short of the IE base, fails the
     * same way.
     */
    @Test
    void addonMachinesExtendingAdmittedClassesAreRefused() {
        assertRefused("com.example.somemod.tile.CustomChargingStationBlockEntity",
                "blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity",
                IMMERSIVE_CONNECTABLE, IE_BASE);
        // A chain that leaves the IE package is not a licence either.
        assertFalse(IeOffloadPolicy.chainAllowed(List.of(
                "blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity",
                "com.example.somemod.tile.SomeBase")));
        // A chain that stops short of the IE base is not either.
        assertFalse(IeOffloadPolicy.chainAllowed(List.of(
                "blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity",
                IMMERSIVE_CONNECTABLE)));
        // Nor is an empty one.
        assertFalse(IeOffloadPolicy.chainAllowed(List.of()));
    }

    private static void assertAllowed(String... chain) {
        assertTrue(IeOffloadPolicy.chainAllowed(List.of(chain)),
                "expected offloadable: " + chain[0]);
    }

    private static void assertRefused(String... chain) {
        assertFalse(IeOffloadPolicy.chainAllowed(List.of(chain)),
                "expected denied: " + chain[0]);
    }
}