package com.taolesi.threadtearer.experiment;

import com.taolesi.threadtearer.api.OffloadOutcome;
import com.taolesi.threadtearer.runtime.MCTRuntimeImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile the framework's per-event overhead in isolation — we can't run a
 * real Minecraft server here, but we can measure what {@code steal()} /
 * {@code runApply()} cost when there is no Minecraft work inside. That
 * tells us the floor; anything the user sees in-game is this floor plus
 * the actual {@code Level.setBlock} / tile-entity work.
 *
 * <p>Avoids {@code MCThreadConfig} (which static-initialises NeoForge types
 * and breaks the test classpath) by going around it.
 */
class EndToEndTimingTest {

    private ExecutorService ownerPool;

    @BeforeEach
    void setup() {
        // Warmed up
        for (int i = 0; i < 50_000; i++) {
            InteractionRelocator.isInsideRelocatedVanilla();
        }
        ownerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-server-thread");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void teardown() {
        InteractionRelocator.leaveCompute();
        InteractionRelocator.leaveInteractionThread();
        InteractionRelocator.leaveTickThread();
        ownerPool.shutdownNow();
    }

    /**
     * The user reports "explosive" interaction latency. That latency has
     * three components:
     * <ol>
     *   <li>Packet sits in network queue waiting for server thread to free
     *       up — measured in TICKS, not microseconds.</li>
     *   <li>Framework overhead per packet (mixin → steal → runApply).</li>
     *   <li>The actual vanilla work inside the apply (Level.setBlock,
     *       tile-entity, neighbor updates, packet fan-out) — this is the
     *       big one.</li>
     * </ol>
     * This test isolates (2). We pretend the vanilla work takes 50µs and
     * measure what the framework adds.
     */
    @Test
    void framework_overhead_per_packet() throws InterruptedException {
        // Each "packet" does 50µs of vanilla work (a representative
        // setBlock + neighbour update for a non-busy chunk).
        final long vanillaWorkMicros = 50;
        final int packets = 200;
        final long[] perPacketNanos = new long[packets];

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        // Simulate: server thread receives packets back-to-back.
        Thread server = new Thread(() -> {
            long start = System.nanoTime();
            try {
                for (int i = 0; i < packets; i++) {
                    long t0 = System.nanoTime();
                    // Simulate runApply: APPLY_DEPTH += 1, call vanilla, APPLY_DEPTH -= 1
                    InteractionRelocator.runApply("simulated",
                            () -> {
                                try {
                                    Thread.sleep(0, (int) (vanillaWorkMicros * 1000));
                                } catch (InterruptedException ignored) {
                                }
                            });
                    perPacketNanos[i] = System.nanoTime() - t0;
                }
            } catch (Throwable t) {
                err.set(t);
            } finally {
                long elapsed = System.nanoTime() - start;
                System.out.printf("[timing] %d packets × %d µs vanilla + runApply overhead: %.2f ms total (%.2f µs per packet)%n",
                        packets, vanillaWorkMicros,
                        elapsed / 1_000_000.0,
                        elapsed / 1_000.0 / packets);
                double min = Double.POSITIVE_INFINITY, max = 0, sum = 0;
                for (long n : perPacketNanos) {
                    double us = n / 1_000.0;
                    if (us < min) min = us;
                    if (us > max) max = us;
                    sum += us;
                }
                System.out.printf("[timing] per-packet min=%.2f µs avg=%.2f µs max=%.2f µs%n",
                        min, sum / packets, max);
                done.countDown();
            }
        }, "fake-server");
        server.setDaemon(true);
        server.start();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        server.join();
        if (err.get() != null) throw new AssertionError(err.get());
    }

    /**
     * Measure how many {@code runApply} calls a single-thread server can
     * dispatch per second when there's no vanilla work inside (worst-case
     * for the framework, best-case for vanilla).
     */
    @Test
    void apply_throughput_noVanillaWork() throws InterruptedException {
        final int iters = 10_000;
        CountDownLatch done = new CountDownLatch(1);
        AtomicLong totalNs = new AtomicLong();
        Thread server = new Thread(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                InteractionRelocator.runApply("sim", () -> {});
            }
            totalNs.set(System.nanoTime() - start);
            done.countDown();
        }, "fake-server-throughput");
        server.setDaemon(true);
        server.start();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        server.join();
        long elapsedNs = totalNs.get();
        double perCallMicros = elapsedNs / 1_000.0 / iters;
        System.out.printf("[timing] runApply() no-op on server thread: %.3f µs/call (= %.0f ops/sec)%n",
                perCallMicros,
                1_000_000_000.0 / elapsedNs * iters);
    }

    /**
     * Simulate compute thread offloading: 100 BE ticks × 5 setBlocks.
     * With a WriteCoalescer in place, the off-thread writeCoalescer
     * should accumulate and the server thread processes the batch once.
     */
    @Test
    void simulate_mekBeTick_throughput() throws InterruptedException {
        // The test can't easily reach MCTRuntimeImpl's private WriteCoalescer
        // without a MinecraftServer, so we use a simpler model: queue 1000
        // "setBlock" runnables to a single-thread executor and time how long
        // the executor takes to drain them. This simulates what the server
        // thread experiences during a WriteCoalescer flush.
        final int totalWrites = 1000;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> elapsed = new AtomicReference<>();
        ownerPool.submit(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < totalWrites; i++) {
                // Simulate a representative vanilla setBlock: ~50µs of work
                // (chunk section setBlockState + tile entity update + packet).
                try {
                    Thread.sleep(0, 50_000);
                } catch (InterruptedException ignored) {
                }
            }
            elapsed.set(System.nanoTime() - start);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        long ns = elapsed.get();
        System.out.printf("[timing] server-thread drains %d simulated setBlocks: %.2f ms (%.2f µs each)%n",
                totalWrites, ns / 1_000_000.0, ns / 1_000.0 / totalWrites);
        System.out.printf("[timing] => per game tick (20 TPS = 50ms budget): this eats %.1f%% of tick%n",
                ns / 1_000_000.0 / 50.0 * 100.0);
    }

    /**
     * Hypothesis: the WriteCoalescer pattern (queue then drain once per tick)
     * is better than the old per-write s.execute, but only if each write is
     * light. With heavy writes (setBlock + neighbor updates) the server thread
     * is still bottlenecked on the actual work, not the queue.
     */
    @Test
    void coalescer_vs_immediate_throughput() throws InterruptedException {
        // Two scenarios: every write is an s.execute (immediate), vs every
        // write goes into a coalescer that flushes once.
        final int writes = 500;
        // Scenario A: per-write inline dispatch
        final long[] aTimes = new long[1];
        CountDownLatch doneA = new CountDownLatch(1);
        ownerPool.submit(() -> {
            long start = System.nanoTime();
            for (int i = 0; i < writes; i++) {
                try { Thread.sleep(0, 50_000); } catch (InterruptedException ignored) { }
            }
            aTimes[0] = System.nanoTime() - start;
            doneA.countDown();
        });
        doneA.await(30, TimeUnit.SECONDS);

        // Scenario B: coalesced flush (all writes batched into one server
        // task). To model: still have to drain serially because they're all
        // on the single server thread. So this is the same as A.
        // The point: batching does NOT save server thread time when each
        // write is heavy. It only saves queue/dispatch overhead.
        System.out.printf("[timing] %d setBlocks serially on server thread: %.2f ms%n",
                writes, aTimes[0] / 1_000_000.0);
        System.out.printf("[timing] => WriteCoalescer batches dispatch but cannot parallelise the actual setBlock work.%n");
        System.out.printf("[timing] => The real question is: how many setBlocks does the server do per tick? If 1000, the server thread is 50ms = the WHOLE tick.%n");
    }
}