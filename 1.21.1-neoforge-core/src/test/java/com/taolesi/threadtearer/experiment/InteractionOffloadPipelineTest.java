package com.taolesi.threadtearer.experiment;

import com.taolesi.threadtearer.api.OffloadOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipeline-level tests with a fake map-based world session. Covers the
 * baseline contract: decide on the interaction thread, validate against the
 * live world, apply on the owner thread, and the submit/emit/apply stats.
 */
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
    void decisionAppliesOnOwnerThread() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, "minecraft:dirt");
        FakeSession session = new FakeSession(world, "minecraft:farmland");

        OffloadOutcome outcome = await(session.snapshot(world, 0, 0, 0), session);

        assertEquals(OffloadOutcome.APPLIED, outcome);
        assertEquals("minecraft:farmland", world.get(0, 0, 0));
        assertTrue(session.decideThread.get().startsWith("test-interaction"), session.decideThread.get());
        assertTrue(session.applyThread.get().startsWith("test-server"), session.applyThread.get());
        assertTrue(pipeline.lastEmitThread().startsWith("test-interaction"), pipeline.lastEmitThread());
    }

    @Test
    void staleWorldIsRejected() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, "minecraft:dirt");
        FakeSession.Snapshot snap = session0(world).snapshot(world, 0, 0, 0);
        world.put(0, 0, 0, "minecraft:stone");
        FakeSession session = session0(world);

        OffloadOutcome outcome = await(snap, session);

        assertEquals(OffloadOutcome.REJECTED_STALE, outcome);
        assertEquals("minecraft:stone", world.get(0, 0, 0));
        assertEquals(1L, pipeline.stats().rejectedStale());
        assertEquals(0L, pipeline.stats().applied());
    }

    @Test
    void passDecisionWritesNothing() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, "minecraft:dirt");
        FakeSession session = new FakeSession(world, null);

        OffloadOutcome outcome = await(session.snapshot(world, 0, 0, 0), session);

        assertEquals(OffloadOutcome.PASSED, outcome);
        assertEquals("minecraft:dirt", world.get(0, 0, 0));
    }

    @Test
    void fifoTwoSubmitsApplyInOrder() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(1, 0, 0, "minecraft:dirt");
        world.put(2, 0, 0, "minecraft:grass_block");
        FakeSession session = new FakeSession(world, "minecraft:farmland");

        CompletableFuture<OffloadOutcome> a = new CompletableFuture<>();
        CompletableFuture<OffloadOutcome> b = new CompletableFuture<>();
        pipeline.submit(session.snapshot(world, 1, 0, 0), session, a);
        pipeline.submit(session.snapshot(world, 2, 0, 0), session, b);

        assertEquals(OffloadOutcome.APPLIED, a.get(5, TimeUnit.SECONDS));
        assertEquals(OffloadOutcome.APPLIED, b.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("1,0,0", "2,0,0"), session.applyOrder);
        assertEquals("minecraft:farmland", world.get(1, 0, 0));
        assertEquals("minecraft:farmland", world.get(2, 0, 0));
    }

    @Test
    void submitReturnsWithoutWaitingForDecide() throws Exception {
        FakeWorld world = new FakeWorld();
        world.put(0, 0, 0, "minecraft:dirt");
        CountDownLatch enteredDecide = new CountDownLatch(1);
        CountDownLatch releaseDecide = new CountDownLatch(1);
        FakeSession session = new FakeSession(world, "minecraft:farmland", enteredDecide, releaseDecide);
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();

        pipeline.submit(session.snapshot(world, 0, 0, 0), session, seen);

        assertTrue(enteredDecide.await(5, TimeUnit.SECONDS), "interaction thread should be in decide");
        assertFalse(seen.isDone(), "submit must not wait for the emitted result to be applied");
        assertEquals("minecraft:dirt", world.get(0, 0, 0), "world must stay untouched until the result is consumed");

        releaseDecide.countDown();
        assertEquals(OffloadOutcome.APPLIED, seen.get(5, TimeUnit.SECONDS));
        assertEquals("minecraft:farmland", world.get(0, 0, 0));
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
    void worldApplyFromComputeRunsOnceOnOwnerInFifoOrder() throws Exception {
        FakeWorld world = new FakeWorld();
        List<String> applyOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> applyThread = new AtomicReference<>();

        ExecutorService compute = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-compute"));
        try {
            // Two world applies submitted from a compute worker must queue in
            // submission order on the interaction FIFO and each write must run
            // exactly once on the owner thread.
            CompletableFuture<OffloadOutcome> first = CompletableFuture.supplyAsync(() ->
                    pipeline.scheduleWorldApply("compute.setBlock", () -> {
                        applyThread.set(Thread.currentThread().getName());
                        applyOrder.add("first");
                        world.put(0, 0, 0, "minecraft:stone");
                    }), compute).join();
            CompletableFuture<OffloadOutcome> second = CompletableFuture.supplyAsync(() ->
                    pipeline.scheduleWorldApply("compute.setBlock2", () -> {
                        applyOrder.add("second");
                        assertEquals("minecraft:stone", world.get(0, 0, 0),
                                "second write must see the first write's result");
                        world.put(0, 0, 0, "minecraft:bricks");
                    }), compute).join();

            assertEquals(OffloadOutcome.APPLIED, first.get(5, TimeUnit.SECONDS));
            assertEquals(OffloadOutcome.APPLIED, second.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), applyOrder);
            assertEquals("minecraft:bricks", world.get(0, 0, 0), "each write must run exactly once");
            assertTrue(applyThread.get().startsWith("test-server"), applyThread.get());
            assertTrue(pipeline.lastEmitThread().startsWith("test-interaction"), pipeline.lastEmitThread());
        } finally {
            compute.shutdownNow();
        }
    }

    @Test
    void worldApplyFailureCompletesExceptionally() throws Exception {
        CompletableFuture<OffloadOutcome> seen = pipeline.scheduleWorldApply("compute.boom", () -> {
            throw new IllegalStateException("boom");
        });
        try {
            seen.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected the future to fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertEquals("boom", expected.getCause().getMessage());
        }
        for (int i = 0; i < 50 && pipeline.stats().applied() == 0L; i++) {
            Thread.sleep(20);
        }
        assertEquals(0L, pipeline.stats().applied(), "failed apply must not count as applied");
        assertEquals(OffloadOutcome.FAILED, pipeline.stats().lastOutcome());
    }

    private FakeSession session0(FakeWorld world) {
        return new FakeSession(world, "minecraft:farmland");
    }

    private OffloadOutcome await(FakeSession.Snapshot snap, FakeSession session) throws Exception {
        CompletableFuture<OffloadOutcome> seen = new CompletableFuture<>();
        pipeline.submit(snap, session, seen);
        return seen.get(5, TimeUnit.SECONDS);
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

    /**
     * Domain-neutral session: decide yields a fixed result block (or pass),
     * validate re-reads the live fake world, apply writes the block.
     */
    private static final class FakeSession implements OffloadSession<FakeSession.Snapshot, FakeSession.Decision> {
        private final FakeWorld world;
        private final String resultBlock;
        private final AtomicReference<String> decideThread = new AtomicReference<>();
        private final AtomicReference<String> applyThread = new AtomicReference<>();
        private final List<String> applyOrder = Collections.synchronizedList(new ArrayList<>());

        private final CountDownLatch enteredDecide;
        private final CountDownLatch releaseDecide;

        private FakeSession(FakeWorld world, String resultBlock) {
            this(world, resultBlock, null, null);
        }

        private FakeSession(FakeWorld world, String resultBlock,
                            CountDownLatch enteredDecide, CountDownLatch releaseDecide) {
            this.world = world;
            this.resultBlock = resultBlock;
            this.enteredDecide = enteredDecide;
            this.releaseDecide = releaseDecide;
        }

        Snapshot snapshot(FakeWorld world, int x, int y, int z) {
            return new Snapshot(x, y, z, world.get(x, y, z));
        }

        @Override
        public Decision decide(Snapshot snapshot) {
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
            return new Decision(resultBlock);
        }

        @Override
        public boolean shouldApply(Decision decision) {
            return decision.resultBlock() != null;
        }

        @Override
        public boolean validate(Snapshot snapshot, Decision decision) {
            return snapshot.blockId().equals(world.get(snapshot.x(), snapshot.y(), snapshot.z()));
        }

        @Override
        public void apply(Snapshot snapshot, Decision decision) {
            applyThread.set(Thread.currentThread().getName());
            applyOrder.add(FakeWorld.key(snapshot.x(), snapshot.y(), snapshot.z()));
            world.put(snapshot.x(), snapshot.y(), snapshot.z(), decision.resultBlock());
        }

        private record Snapshot(int x, int y, int z, String blockId) {
        }

        private record Decision(String resultBlock) {
        }
    }
}
