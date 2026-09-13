package com.taolesi.mcthread.experiment;

import com.taolesi.mcthread.experiment.till.TillDecision;
import com.taolesi.mcthread.experiment.till.TillRules;
import com.taolesi.mcthread.experiment.till.TillSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionOffloadPipelineTest {

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
    void hoeOnDirtAppliesFarmlandOnOwnerThread() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, TillRules.DIRT);
        TillSnapshot snap = snapshot(world, 0, 0, 0, true);
        FakeTillSession session = new FakeTillSession(world);

        OffloadOutcome outcome = await(snap, session);

        assertEquals(OffloadOutcome.APPLIED, outcome);
        assertEquals(TillRules.FARMLAND, world.get(0, 0, 0));
        assertTrue(session.decideThread.get().startsWith("test-interaction"), session.decideThread.get());
        assertTrue(session.applyThread.get().startsWith("test-server"), session.applyThread.get());
        assertTrue(pipeline.lastEmitThread().startsWith("test-interaction"), pipeline.lastEmitThread());
    }

    @Test
    void staleBlockIsRejected() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, TillRules.DIRT);
        TillSnapshot snap = snapshot(world, 0, 0, 0, true);
        world.put(0, 0, 0, "minecraft:stone");
        FakeTillSession session = new FakeTillSession(world);

        OffloadOutcome outcome = await(snap, session);

        assertEquals(OffloadOutcome.REJECTED_STALE, outcome);
        assertEquals("minecraft:stone", world.get(0, 0, 0));
        assertEquals(1L, pipeline.stats().rejectedStale());
        assertEquals(0L, pipeline.stats().applied());
    }

    @Test
    void nonHoePassesWithoutWrite() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, TillRules.DIRT);
        TillSnapshot snap = snapshot(world, 0, 0, 0, false);
        FakeTillSession session = new FakeTillSession(world);

        OffloadOutcome outcome = await(snap, session);

        assertEquals(OffloadOutcome.PASSED, outcome);
        assertEquals(TillRules.DIRT, world.get(0, 0, 0));
    }

    @Test
    void fifoTwoTills() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(1, 0, 0, TillRules.DIRT);
        world.put(2, 0, 0, TillRules.GRASS_BLOCK);
        FakeTillSession session = new FakeTillSession(world);

        OffloadOutcome first;
        OffloadOutcome second;
        CompletableFuture<OffloadOutcome> a = new CompletableFuture<>();
        CompletableFuture<OffloadOutcome> b = new CompletableFuture<>();
        pipeline.submit(snapshot(world, 1, 0, 0, true), session, a);
        pipeline.submit(snapshot(world, 2, 0, 0, true), session, b);
        first = a.get(5, TimeUnit.SECONDS);
        second = b.get(5, TimeUnit.SECONDS);

        assertEquals(OffloadOutcome.APPLIED, first);
        assertEquals(OffloadOutcome.APPLIED, second);
        assertEquals(List.of("1,0,0", "2,0,0"), session.applyOrder);
        assertEquals(TillRules.FARMLAND, world.get(1, 0, 0));
        assertEquals(TillRules.FARMLAND, world.get(2, 0, 0));
    }

    @Test
    void coarseDirtBecomesDirt() {
        TillSnapshot snap = new TillSnapshot(
                0L, "minecraft:overworld", 0, 0, 0,
                TillRules.COARSE_DIRT, true, "minecraft:iron_hoe",
                true, true, false, new UUID(0, 1));
        TillDecision decision = TillRules.decide(snap);
        assertTrue(decision.apply());
        assertEquals(TillRules.DIRT, decision.resultBlockId());
    }

    @Test
    void submitReturnsWithoutWaitingForDecide() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, TillRules.DIRT);
        CountDownLatch enteredDecide = new CountDownLatch(1);
        CountDownLatch releaseDecide = new CountDownLatch(1);
        FakeTillSession session = new FakeTillSession(world, enteredDecide, releaseDecide);
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();

        pipeline.submit(snapshot(world, 0, 0, 0, true), session, seen);

        assertTrue(enteredDecide.await(5, TimeUnit.SECONDS), "interaction thread should be in decide");
        assertFalse(seen.isDone(), "submit must not wait for the emitted result to be applied");
        assertEquals(TillRules.DIRT, world.get(0, 0, 0), "world must stay untouched until the result is consumed");

        releaseDecide.countDown();
        assertEquals(OffloadOutcome.APPLIED, seen.get(5, TimeUnit.SECONDS));
        assertEquals(TillRules.FARMLAND, world.get(0, 0, 0));
        assertEquals(1L, pipeline.stats().emitted());
    }

    @Test
    void relocateVanillaComputesOnInteractionAppliesOnOwnerWithoutWaiting() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> runThread = new AtomicReference<>();
        pipeline.relocateVanilla(() -> {
            runThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS), "apply should run on the owner thread");
        assertTrue(runThread.get().startsWith("test-server"), runThread.get());
        assertTrue(pipeline.lastEmitThread().startsWith("test-interaction"), pipeline.lastEmitThread());
        assertEquals(1L, pipeline.stats().submitted());
        assertEquals(1L, pipeline.stats().emitted());
        assertEquals(0L, pipeline.stats().applied(), "submit must return before owner apply finishes");
        release.countDown();
        for (int i = 0; i < 50 && pipeline.stats().applied() == 0L; i++) {
            Thread.sleep(20);
        }
        assertEquals(1L, pipeline.stats().applied());
        assertEquals(OffloadOutcome.APPLIED, pipeline.stats().lastOutcome());
    }

    @Test
    void relocateTickRunsBlockEntityOnComputeExecutor() throws Exception {
        ExecutorService compute = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-compute"));
        try {
            pipeline = new InteractionOffloadPipeline(owner, interaction, compute);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> runThread = new AtomicReference<>();
            pipeline.relocateTick(() -> {
                runThread.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "block-entity tick should run on the compute executor");
            assertTrue(runThread.get().startsWith("test-compute"), runThread.get());
            assertTrue(pipeline.lastEmitThread().startsWith("test-compute"), pipeline.lastEmitThread());
        } finally {
            compute.shutdownNow();
        }
    }

    @Test
    void blockedByBlockAbove() {
        TillSnapshot snap = new TillSnapshot(
                0L, "minecraft:overworld", 0, 0, 0,
                TillRules.DIRT, false, "minecraft:iron_hoe",
                true, true, false, new UUID(0, 1));
        assertFalse(TillRules.decide(snap).apply());
    }

    private OffloadOutcome await(TillSnapshot snap, FakeTillSession session) throws Exception {
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();
        pipeline.submit(snap, session, seen);
        return seen.get(5, TimeUnit.SECONDS);
    }

    private static TillSnapshot snapshot(FakeWorld world, int x, int y, int z, boolean hoe) {
        return new TillSnapshot(
                System.nanoTime(),
                "minecraft:overworld",
                x, y, z,
                world.get(x, y, z),
                true,
                hoe ? "minecraft:iron_hoe" : "minecraft:stick",
                hoe,
                true,
                false,
                new UUID(0, 1));
    }

    private static final class FakeWorld {
        private final Map<String, String> blocks = new HashMap<>();

        void put(int x, int y, int z, String id) {
            blocks.put(key(x, y, z), id);
        }

        String get(int x, int y, int z) {
            return blocks.get(key(x, y, z));
        }

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    private static final class FakeTillSession implements OffloadSession<TillSnapshot, TillDecision> {
        private final FakeWorld world;
        private final AtomicReference<String> decideThread = new AtomicReference<>();
        private final AtomicReference<String> applyThread = new AtomicReference<>();
        private final List<String> applyOrder = Collections.synchronizedList(new ArrayList<>());

        private final CountDownLatch enteredDecide;
        private final CountDownLatch releaseDecide;

        private FakeTillSession(FakeWorld world) {
            this(world, null, null);
        }

        private FakeTillSession(FakeWorld world, CountDownLatch enteredDecide, CountDownLatch releaseDecide) {
            this.world = world;
            this.enteredDecide = enteredDecide;
            this.releaseDecide = releaseDecide;
        }

        @Override
        public TillDecision decide(TillSnapshot snapshot) {
            decideThread.set(Thread.currentThread().getName());
            if (enteredDecide != null) {
                enteredDecide.countDown();
            }
            if (releaseDecide != null) {
                try {
                    if (!releaseDecide.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("decide was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return TillRules.decide(snapshot);
        }

        @Override
        public boolean shouldApply(TillDecision decision) {
            return decision.apply();
        }

        @Override
        public boolean validate(TillSnapshot snapshot, TillDecision decision) {
            String live = world.get(snapshot.x(), snapshot.y(), snapshot.z());
            return snapshot.blockId().equals(live) && snapshot.airAbove();
        }

        @Override
        public void apply(TillSnapshot snapshot, TillDecision decision) {
            applyThread.set(Thread.currentThread().getName());
            applyOrder.add(FakeWorld.key(snapshot.x(), snapshot.y(), snapshot.z()));
            world.put(snapshot.x(), snapshot.y(), snapshot.z(), decision.resultBlockId());
        }
    }
}
