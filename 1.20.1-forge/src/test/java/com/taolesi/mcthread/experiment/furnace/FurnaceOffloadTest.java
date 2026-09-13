package com.taolesi.mcthread.experiment.furnace;

import com.taolesi.mcthread.experiment.InteractionOffloadPipeline;
import com.taolesi.mcthread.experiment.OffloadOutcome;
import com.taolesi.mcthread.experiment.OffloadSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FurnaceOffloadTest {

    private ExecutorService owner;
    private ExecutorService interaction;
    private InteractionOffloadPipeline pipeline;

    @BeforeEach
    void setUp() {
        owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-server"));
        interaction = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-interaction"));
        pipeline = new InteractionOffloadPipeline(owner, interaction);
    }

    @AfterEach
    void tearDown() {
        owner.shutdownNow();
        interaction.shutdownNow();
    }

    @Test
    void openEmitsOpenMenu() {
        FurnaceDecision decision = FurnaceRules.decide(openSnapshot());
        assertTrue(decision.opensMenu());
        assertTrue(decision.apply());
    }

    @Test
    void pickupPlacesFuel() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        FurnaceSnapshot snap = click("PICKUP", FurnaceSnapshot.FUEL, 0, slots, coal());
        FakeSession session = new FakeSession(world);

        assertEquals(OffloadOutcome.APPLIED, await(snap, session));
        assertEquals("minecraft:coal", world.slots[1].id());
        assertTrue(world.carried.isEmpty());
        assertTrue(session.decideThread.get().startsWith("test-interaction"));
        assertTrue(session.applyThread.get().startsWith("test-server"));
    }

    @Test
    void pickupPlacesIngredient() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        assertEquals(OffloadOutcome.APPLIED,
                await(click("PICKUP", FurnaceSnapshot.INGREDIENT, 0, slots, rawIron()), new FakeSession(world)));
        assertEquals("minecraft:raw_iron", world.slots[0].id());
    }

    @Test
    void fuelSlotRejectsNonFuel() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        assertEquals(OffloadOutcome.PASSED,
                await(click("PICKUP", FurnaceSnapshot.FUEL, 0, slots, rawIron()), new FakeSession(world)));
        assertTrue(world.slots[1].isEmpty());
    }

    @Test
    void pickupTakesResult() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[2] = ingot();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        assertEquals(OffloadOutcome.APPLIED,
                await(click("PICKUP", FurnaceSnapshot.RESULT, 0, slots, SlotCopy.EMPTY), new FakeSession(world)));
        assertTrue(world.slots[2].isEmpty());
        assertEquals("minecraft:iron_ingot", world.carried.id());
    }

    @Test
    void resultSlotRejectsInsert() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        assertEquals(OffloadOutcome.PASSED,
                await(click("PICKUP", FurnaceSnapshot.RESULT, 0, slots, coal()), new FakeSession(world)));
        assertTrue(world.slots[2].isEmpty());
    }

    @Test
    void quickMoveIngredientFromHotbar() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[FurnaceSnapshot.HOTBAR_START] = rawIron();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        assertEquals(OffloadOutcome.APPLIED,
                await(click("QUICK_MOVE", FurnaceSnapshot.HOTBAR_START, 0, slots, SlotCopy.EMPTY),
                        new FakeSession(world)));
        assertEquals("minecraft:raw_iron", world.slots[0].id());
        assertTrue(world.slots[FurnaceSnapshot.HOTBAR_START].isEmpty());
    }

    @Test
    void staleIngredientIsRejected() throws Exception {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FakeFurnace world = new FakeFurnace(slots, SlotCopy.EMPTY);
        FurnaceSnapshot snap = click("PICKUP", FurnaceSnapshot.FUEL, 0, slots, coal());
        world.slots[0] = rawIron();
        assertEquals(OffloadOutcome.REJECTED_STALE, await(snap, new FakeSession(world)));
        assertTrue(world.slots[1].isEmpty());
    }

    @Test
    void pickupOutsideDropsCarried() {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        FurnaceDecision decision = FurnaceRules.decide(click("PICKUP", -999, 0, slots, coal()));
        assertTrue(decision.apply());
        assertTrue(decision.carried().isEmpty());
        assertEquals("minecraft:coal", decision.drop().id());
        assertEquals(8, decision.drop().count());
    }

    @Test
    void throwDropsOneFromFuel() {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[1] = coal();
        FurnaceDecision decision = FurnaceRules.decide(click("THROW", FurnaceSnapshot.FUEL, 0, slots, SlotCopy.EMPTY));
        assertTrue(decision.apply());
        assertEquals(7, decision.slots()[1].count());
        assertEquals("minecraft:coal", decision.drop().id());
        assertEquals(1, decision.drop().count());
    }

    @Test
    void swapMovesResultToHotbar() {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[2] = ingot();
        FurnaceDecision decision = FurnaceRules.decide(click("SWAP", 2, 0, slots, SlotCopy.EMPTY));
        assertTrue(decision.apply());
        assertTrue(decision.slots()[2].isEmpty());
        assertEquals("minecraft:iron_ingot", decision.slots()[FurnaceSnapshot.HOTBAR_START].id());
    }

    @Test
    void pickupAllGathersCoalIntoCarried() {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[1] = new SlotCopy("minecraft:coal", 8, 64, false, true);
        slots[3] = new SlotCopy("minecraft:coal", 4, 64, false, true);
        FurnaceDecision decision = FurnaceRules.decide(click("PICKUP_ALL", FurnaceSnapshot.INGREDIENT, 0, slots, coal()));
        assertTrue(decision.apply());
        assertEquals(20, decision.carried().count());
        assertTrue(decision.slots()[1].isEmpty());
        assertTrue(decision.slots()[3].isEmpty());
    }

    @Test
    void cloneFillsCarriedInCreative() {
        SlotCopy[] slots = FurnaceSnapshot.emptySlots();
        slots[1] = coal();
        FurnaceSnapshot snap = new FurnaceSnapshot("CLONE", 1, 0, "minecraft:overworld", 0, 0, 0,
                "minecraft:furnace", 1, slots, SlotCopy.EMPTY, true, new UUID(0, 1));
        FurnaceDecision decision = FurnaceRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(64, decision.carried().count());
        assertEquals(8, decision.slots()[1].count());
    }

    private OffloadOutcome await(FurnaceSnapshot snap, FakeSession session) throws Exception {
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();
        pipeline.submit(snap, session, seen);
        return seen.get(5, TimeUnit.SECONDS);
    }

    private static FurnaceSnapshot openSnapshot() {
        return new FurnaceSnapshot("OPEN", -1, 0, "minecraft:overworld", 0, 0, 0,
                "minecraft:furnace", -1, FurnaceSnapshot.emptySlots(), SlotCopy.EMPTY, false, new UUID(0, 1));
    }

    private static FurnaceSnapshot click(String kind, int slot, int button, SlotCopy[] slots, SlotCopy carried) {
        return new FurnaceSnapshot(kind, slot, button, "minecraft:overworld", 0, 0, 0,
                "minecraft:furnace", 1, slots, carried, false, new UUID(0, 1));
    }

    private static SlotCopy coal() {
        return new SlotCopy("minecraft:coal", 8, 64, false, true);
    }

    private static SlotCopy rawIron() {
        return new SlotCopy("minecraft:raw_iron", 1, 64, true, false);
    }

    private static SlotCopy ingot() {
        return new SlotCopy("minecraft:iron_ingot", 1, 64, false, false);
    }

    private static final class FakeFurnace {
        private final SlotCopy[] slots;
        private SlotCopy carried;

        private FakeFurnace(SlotCopy[] slots, SlotCopy carried) {
            this.slots = slots.clone();
            this.carried = carried;
        }
    }

    private static final class FakeSession implements OffloadSession<FurnaceSnapshot, FurnaceDecision> {
        private final FakeFurnace world;
        private final AtomicReference<String> decideThread = new AtomicReference<>();
        private final AtomicReference<String> applyThread = new AtomicReference<>();

        private FakeSession(FakeFurnace world) {
            this.world = world;
        }

        @Override
        public FurnaceDecision decide(FurnaceSnapshot snapshot) {
            decideThread.set(Thread.currentThread().getName());
            return FurnaceRules.decide(snapshot);
        }

        @Override
        public boolean shouldApply(FurnaceDecision decision) {
            return decision.apply();
        }

        @Override
        public boolean validate(FurnaceSnapshot snapshot, FurnaceDecision decision) {
            if (decision.opensMenu()) {
                return true;
            }
            for (int i = 0; i < 3; i++) {
                SlotCopy live = world.slots[i];
                SlotCopy expected = snapshot.slots()[i];
                if (live.isEmpty() != expected.isEmpty()) {
                    return false;
                }
                if (!live.isEmpty() && (!live.id().equals(expected.id()) || live.count() != expected.count())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void apply(FurnaceSnapshot snapshot, FurnaceDecision decision) {
            applyThread.set(Thread.currentThread().getName());
            if (decision.slots() != null) {
                System.arraycopy(decision.slots(), 0, world.slots, 0, world.slots.length);
            }
            world.carried = decision.carried();
        }
    }
}
