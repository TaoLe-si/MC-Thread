package com.taolesi.mcthread.experiment.menu;

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

class MenuOffloadTest {

    private static final String[] TYPES = {"minecraft:cobblestone"};
    private static final long PLACE_ALL = 1L;

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
        MenuDecision decision = MenuRules.decide(openSnapshot());
        assertTrue(decision.opensMenu());
        assertTrue(decision.apply());
    }

    @Test
    void pickupPlacesIntoChestSlot() throws Exception {
        MenuSlotCopy[] slots = chestSlots();
        FakeChest world = new FakeChest(slots);
        assertEquals(OffloadOutcome.APPLIED,
                await(click("PICKUP", 0, 0, slots, cobble(8)), new FakeSession(world)));
        assertEquals("minecraft:cobblestone", world.slots[0].id());
        assertEquals(8, world.slots[0].count());
        assertTrue(world.carried.isEmpty());
    }

    @Test
    void quickMoveFromHotbarIntoChest() throws Exception {
        MenuSlotCopy[] slots = chestSlots();
        slots[27 + 27] = cobble(16);
        FakeChest world = new FakeChest(slots);
        assertEquals(OffloadOutcome.APPLIED,
                await(click("QUICK_MOVE", 54, 0, slots, MenuSlotCopy.EMPTY), new FakeSession(world)));
        assertEquals("minecraft:cobblestone", world.slots[0].id());
        assertTrue(world.slots[54].isEmpty());
    }

    @Test
    void pickupAllGathersFromChest() {
        MenuSlotCopy[] slots = chestSlots();
        slots[0] = cobble(8);
        slots[1] = cobble(4);
        MenuDecision decision = MenuRules.decide(click("PICKUP_ALL", 2, 0, slots, cobble(1)));
        assertTrue(decision.apply());
        assertEquals(13, decision.carried().count());
        assertTrue(decision.slots()[0].isEmpty());
        assertTrue(decision.slots()[1].isEmpty());
    }

    @Test
    void cloneFillsCarried() {
        MenuSlotCopy[] slots = chestSlots();
        slots[0] = cobble(8);
        MenuSnapshot snap = new MenuSnapshot("CLONE", 0, 0, "minecraft:overworld", 0, 0, 0,
                "minecraft:chest", 1, 27, TYPES, slots, MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY,
                true, "main_hand", "up", new UUID(0, 1));
        MenuDecision decision = MenuRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(64, decision.carried().count());
        assertEquals(8, decision.slots()[0].count());
    }

    @Test
    void staleChestSlotIsRejected() throws Exception {
        MenuSlotCopy[] slots = chestSlots();
        FakeChest world = new FakeChest(slots);
        MenuSnapshot snap = click("PICKUP", 0, 0, slots, cobble(1));
        world.slots[0] = cobble(4);
        assertEquals(OffloadOutcome.REJECTED_STALE, await(snap, new FakeSession(world)));
        assertEquals(4, world.slots[0].count());
    }

    private OffloadOutcome await(MenuSnapshot snap, FakeSession session) throws Exception {
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();
        pipeline.submit(snap, session, seen);
        return seen.get(5, TimeUnit.SECONDS);
    }

    private static MenuSnapshot openSnapshot() {
        return new MenuSnapshot("OPEN", -1, 0, "minecraft:overworld", 0, 0, 0,
                "minecraft:chest", -1, 0, new String[0], MenuSnapshot.emptySlots(0),
                MenuSlotCopy.EMPTY, MenuSlotCopy.EMPTY, false, "main_hand", "up", new UUID(0, 1));
    }

    private static MenuSnapshot click(String kind, int slot, int button, MenuSlotCopy[] slots, MenuSlotCopy carried) {
        return new MenuSnapshot(kind, slot, button, "minecraft:overworld", 0, 0, 0,
                "minecraft:chest", 1, 27, TYPES, slots, carried, MenuSlotCopy.EMPTY,
                false, "main_hand", "up", new UUID(0, 1));
    }

    private static MenuSlotCopy[] chestSlots() {
        MenuSlotCopy[] slots = new MenuSlotCopy[63];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new MenuSlotCopy("minecraft:air", 0, 64, true, PLACE_ALL);
        }
        return slots;
    }

    private static MenuSlotCopy cobble(int n) {
        return new MenuSlotCopy("minecraft:cobblestone", n, 64, true, PLACE_ALL);
    }

    private static final class FakeChest {
        private final MenuSlotCopy[] slots;
        private MenuSlotCopy carried = MenuSlotCopy.EMPTY;

        private FakeChest(MenuSlotCopy[] slots) {
            this.slots = slots.clone();
        }
    }

    private static final class FakeSession implements OffloadSession<MenuSnapshot, MenuDecision> {
        private final FakeChest world;
        private final AtomicReference<String> decideThread = new AtomicReference<>();
        private final AtomicReference<String> applyThread = new AtomicReference<>();

        private FakeSession(FakeChest world) {
            this.world = world;
        }

        @Override
        public MenuDecision decide(MenuSnapshot snapshot) {
            decideThread.set(Thread.currentThread().getName());
            return MenuRules.decide(snapshot);
        }

        @Override
        public boolean shouldApply(MenuDecision decision) {
            return decision.apply();
        }

        @Override
        public boolean validate(MenuSnapshot snapshot, MenuDecision decision) {
            if (decision.opensMenu()) {
                return true;
            }
            for (int i = 0; i < 27; i++) {
                MenuSlotCopy live = world.slots[i];
                MenuSlotCopy expected = snapshot.slots()[i];
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
        public void apply(MenuSnapshot snapshot, MenuDecision decision) {
            applyThread.set(Thread.currentThread().getName());
            if (decision.slots() != null) {
                System.arraycopy(decision.slots(), 0, world.slots, 0, world.slots.length);
            }
            world.carried = decision.carried();
        }
    }
}
