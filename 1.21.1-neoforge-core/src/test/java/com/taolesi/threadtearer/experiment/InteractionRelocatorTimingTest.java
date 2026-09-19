package com.taolesi.threadtearer.experiment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnoses where the cost in {@link InteractionRelocator#steal(Runnable)}
 * lives. Avoids referencing {@code MCThreadConfig} (which static-initialises
 * NeoForge types and breaks the test classpath) and exercises only the
 * thread-local / StackWalker / hot-path primitives directly.
 *
 * <p>Run with: {@code ./gradlew test --tests InteractionRelocatorTimingTest -i}
 */
class InteractionRelocatorTimingTest {

    @BeforeEach
    void warmup() {
        for (int i = 0; i < 50_000; i++) {
            InteractionRelocator.isInsideRelocatedVanilla();
        }
    }

    @AfterEach
    void reset() {
        InteractionRelocator.leaveCompute();
        InteractionRelocator.leaveInteractionThread();
        InteractionRelocator.leaveTickThread();
    }

    /**
     * The single biggest suspect: {@code callerName()} uses StackWalker,
     * which reflects over the stack on every call. {@code steal()} invokes
     * it unconditionally — every packet mixin pays this cost.
     */
    @Test
    void benchmark_callerName_over_typical_mixin_stack() {
        final int iters = 200_000;
        final AtomicLong sum = new AtomicLong();
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            String name = mixinStackCallerName();
            sum.addAndGet(name.length());
        }
        long elapsedNs = System.nanoTime() - start;
        double perCallMicros = elapsedNs / 1_000.0 / iters;
        System.out.printf("[timing] callerName on 14-frame mixin stack: %.3f µs/call%n",
                perCallMicros);
        // Theoretical budget: at 1000 setBlocks/tick, 20 TPS:
        // 1000 * 2.18µs = 2.18ms/tick = 4.4% of a 50ms tick just for StackWalker.
        System.out.printf("[timing] => 1000 calls/tick costs ~%.2f ms/tick%n",
                perCallMicros * 1000 / 1000.0);
        // The real cost will be higher because of JIT inlining and warmup, but
        // this is a useful lower-bound signal.
        assertTrue(perCallMicros < 100, () ->
                "callerName is unreasonably slow: " + perCallMicros + " µs/call");
    }

    /**
     * Stack walking done the same way {@code InteractionRelocator.callerName}
     * does it (filter, strip, fallback).
     */
    private String mixinStackCallerName() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> {
                    String cn = frame.getClassName();
                    int dot = cn.lastIndexOf('.');
                    String simple = dot < 0 ? cn : cn.substring(dot + 1);
                    int inner = simple.lastIndexOf('$');
                    String s = inner < 0 ? simple : simple.substring(inner + 1);
                    return s + "." + frame.getMethodName();
                })
                .filter(n -> !n.startsWith("InteractionRelocator."))
                .findFirst()
                .orElse("unknown"));
    }

    /**
     * Bench the pure cost of the four {@code ThreadLocal.get()} calls in
     * steal()'s preamble. These are cheap but happen on EVERY call.
     */
    @Test
    void benchmark_threadLocalAccessPattern() {
        final int iters = 1_000_000;
        // Warmup
        long warm = 0;
        for (int i = 0; i < 50_000; i++) warm += readAllFlags();
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            readAllFlags();
        }
        long elapsedNs = System.nanoTime() - start;
        double perCallMicros = elapsedNs / 1_000.0 / iters;
        System.out.printf("[timing] 4x ThreadLocal.get() per steal(): %.4f µs/call%n",
                perCallMicros);
        System.out.printf("[timing] => 1000 calls/tick costs ~%.3f ms/tick%n",
                perCallMicros * 1000 / 1000.0);
    }

    private long readAllFlags() {
        long s = 0;
        if (InteractionRelocator.isInsideRelocatedVanilla()) s++;
        if (InteractionRelocator.isOnInteractionThread()) s++;
        if (InteractionRelocator.isOnTickThread()) s++;
        if (InteractionRelocator.isComputing()) s++;
        return s;
    }

    /**
     * Combined estimate: per packet cost. This is the number that determines
     * whether the server thread keeps up with MEK's setBlocks.
     */
    @Test
    void benchmark_perPacketLatencyEstimate() throws InterruptedException {
        // 100 BE ticks × 5 setBlocks each, on a compute thread.
        // This matches the user's "computeTicks=100 per tick" workload.
        final int beTicks = 100;
        final int setBlocksPerBE = 5;
        InteractionRelocator.enterCompute();
        try {
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                long start = System.nanoTime();
                for (int be = 0; be < beTicks; be++) {
                    for (int sb = 0; sb < setBlocksPerBE; sb++) {
                        // Just callerName — that's the only thing steal does on this path
                        // without runtime installed (returns false immediately at rt==null).
                        // But the ThreadLocal read + callerName IS still paid.
                        StackWalker.getInstance().walk(frames -> frames
                                .map(f -> f.getMethodName())
                                .filter(n -> !n.contains("lambda"))
                                .findFirst()
                                .orElse("?"));
                    }
                }
                long elapsedNs = System.nanoTime() - start;
                System.out.printf("[timing] %d BE ticks × %d setBlocks StackWalker-only: %.3f ms total (%.2f µs per call)%n",
                        beTicks, setBlocksPerBE,
                        elapsedNs / 1_000_000.0,
                        elapsedNs / (double) (beTicks * setBlocksPerBE) / 1_000.0);
                System.out.printf("[timing] => This alone eats %.1f%% of a 50ms server tick%n",
                        (elapsedNs / 1_000_000.0) / 50.0 * 100.0);
                done.countDown();
            });
            worker.start();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            worker.join();
        } finally {
            InteractionRelocator.leaveCompute();
        }
    }
}