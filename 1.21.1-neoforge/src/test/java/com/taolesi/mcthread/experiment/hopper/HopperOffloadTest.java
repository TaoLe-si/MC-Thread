package com.taolesi.mcthread.experiment.hopper;

import com.taolesi.mcthread.experiment.InteractionOffloadPipeline;
import com.taolesi.mcthread.experiment.OffloadOutcome;
import com.taolesi.mcthread.experiment.OffloadSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopperOffloadTest {

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
    void ejectsOneIntoDest() throws Exception {
        ItemCopy[] hopper = ItemCopy.empty(5);
        hopper[0] = cobble(4);
        ItemCopy[] dest = ItemCopy.empty(27);
        boolean[] destPlace = allTrue(27 * 5);
        HopperSnapshot snap = HopperSnapshot.transfer(
                "minecraft:overworld", 0, 0, 0,
                1, 0, 0, true,
                0, 1, 0, false,
                hopper, dest, ItemCopy.empty(0),
                destPlace, new boolean[0], new boolean[0]);
        FakeWorld world = new FakeWorld(hopper, dest, ItemCopy.empty(0));
        FakeSession session = new FakeSession(world);
        assertEquals(OffloadOutcome.APPLIED, await(snap, session));
        assertEquals(3, world.hopper[0].count());
        assertEquals("minecraft:cobblestone", world.dest[0].id());
        assertEquals(1, world.dest[0].count());
        assertTrue(session.decideThread.get().startsWith("test-interaction"));
        assertTrue(session.applyThread.get().startsWith("test-server"));
    }

    @Test
    void pullsOneFromSource() throws Exception {
        ItemCopy[] hopper = ItemCopy.empty(5);
        ItemCopy[] source = ItemCopy.empty(27);
        source[0] = cobble(2);
        boolean[] hopperPlace = allTrue(5 * 27);
        boolean[] sourceTake = new boolean[27];
        sourceTake[0] = true;
        HopperSnapshot snap = HopperSnapshot.transfer(
                "minecraft:overworld", 0, 0, 0,
                1, 0, 0, false,
                0, 1, 0, true,
                hopper, ItemCopy.empty(0), source,
                new boolean[0], hopperPlace, sourceTake);
        FakeWorld world = new FakeWorld(hopper, ItemCopy.empty(0), source);
        assertEquals(OffloadOutcome.APPLIED, await(snap, new FakeSession(world)));
        assertEquals("minecraft:cobblestone", world.hopper[0].id());
        assertEquals(1, world.source[0].count());
    }

    @Test
    void staleDestRejects() throws Exception {
        ItemCopy[] hopper = ItemCopy.empty(5);
        hopper[0] = cobble(1);
        ItemCopy[] dest = ItemCopy.empty(27);
        HopperSnapshot snap = HopperSnapshot.transfer(
                "minecraft:overworld", 0, 0, 0,
                1, 0, 0, true,
                0, 1, 0, false,
                hopper, dest, ItemCopy.empty(0),
                allTrue(27 * 5), new boolean[0], new boolean[0]);
        FakeWorld world = new FakeWorld(hopper.clone(), dest.clone(), ItemCopy.empty(0));
        world.dest[0] = cobble(8);
        assertEquals(OffloadOutcome.REJECTED_STALE, await(snap, new FakeSession(world)));
        assertEquals(1, world.hopper[0].count());
    }

    @Test
    void tickCooldownHoldsWithoutMove() {
        ItemCopy[] hopper = ItemCopy.empty(5);
        hopper[0] = cobble(4);
        ItemCopy[] dest = ItemCopy.empty(27);
        HopperSnapshot snap = new HopperSnapshot(
                "minecraft:overworld", 0, 0, 0,
                1, 0, 0, true,
                0, 1, 0, false,
                hopper, dest, ItemCopy.empty(0),
                allTrue(27 * 5), new boolean[0], new boolean[0],
                8, 10L, true);
        HopperDecision decision = HopperRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(7, decision.cooldown());
        assertEquals(4, decision.hopper()[0].count());
        assertTrue(decision.dest()[0].isEmpty());
    }

    @Test
    void tickOnZeroCooldownEjects() {
        ItemCopy[] hopper = ItemCopy.empty(5);
        hopper[0] = cobble(4);
        ItemCopy[] dest = ItemCopy.empty(27);
        HopperSnapshot snap = new HopperSnapshot(
                "minecraft:overworld", 0, 0, 0,
                1, 0, 0, true,
                0, 1, 0, false,
                hopper, dest, ItemCopy.empty(0),
                allTrue(27 * 5), new boolean[0], new boolean[0],
                0, 10L, true);
        HopperDecision decision = HopperRules.decide(snap);
        assertEquals(8, decision.cooldown());
        assertEquals(3, decision.hopper()[0].count());
        assertEquals(1, decision.dest()[0].count());
    }

    private OffloadOutcome await(HopperSnapshot snap, FakeSession session) throws Exception {
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();
        pipeline.submit(snap, session, seen);
        return seen.get(5, TimeUnit.SECONDS);
    }

    private static ItemCopy cobble(int n) {
        return new ItemCopy("minecraft:cobblestone", n, 64);
    }

    private static boolean[] allTrue(int n) {
        boolean[] out = new boolean[n];
        java.util.Arrays.fill(out, true);
        return out;
    }

    private static final class FakeWorld {
        private final ItemCopy[] hopper;
        private final ItemCopy[] dest;
        private final ItemCopy[] source;

        private FakeWorld(ItemCopy[] hopper, ItemCopy[] dest, ItemCopy[] source) {
            this.hopper = hopper.clone();
            this.dest = dest.clone();
            this.source = source.clone();
        }
    }

    private static final class FakeSession implements OffloadSession<HopperSnapshot, HopperDecision> {
        private final FakeWorld world;
        private final AtomicReference<String> decideThread = new AtomicReference<>();
        private final AtomicReference<String> applyThread = new AtomicReference<>();

        private FakeSession(FakeWorld world) {
            this.world = world;
        }

        @Override
        public HopperDecision decide(HopperSnapshot snapshot) {
            decideThread.set(Thread.currentThread().getName());
            return HopperRules.decide(snapshot);
        }

        @Override
        public boolean shouldApply(HopperDecision decision) {
            return decision.apply();
        }

        @Override
        public boolean validate(HopperSnapshot snapshot, HopperDecision decision) {
            return same(world.hopper, snapshot.hopper())
                    && (!snapshot.hasDest() || same(world.dest, snapshot.dest()))
                    && (!snapshot.hasSource() || same(world.source, snapshot.source()));
        }

        @Override
        public void apply(HopperSnapshot snapshot, HopperDecision decision) {
            applyThread.set(Thread.currentThread().getName());
            if (decision.hopper() != null) {
                System.arraycopy(decision.hopper(), 0, world.hopper, 0, world.hopper.length);
            }
            if (decision.dest() != null && world.dest.length > 0) {
                System.arraycopy(decision.dest(), 0, world.dest, 0, world.dest.length);
            }
            if (decision.source() != null && world.source.length > 0) {
                System.arraycopy(decision.source(), 0, world.source, 0, world.source.length);
            }
        }

        private static boolean same(ItemCopy[] live, ItemCopy[] expected) {
            if (expected == null || live.length != expected.length) {
                return false;
            }
            for (int i = 0; i < live.length; i++) {
                ItemCopy a = live[i] == null ? ItemCopy.EMPTY : live[i];
                ItemCopy b = expected[i] == null ? ItemCopy.EMPTY : expected[i];
                if (a.isEmpty() != b.isEmpty()) {
                    return false;
                }
                if (!a.isEmpty() && (!a.id().equals(b.id()) || a.count() != b.count())) {
                    return false;
                }
            }
            return true;
        }
    }
}
