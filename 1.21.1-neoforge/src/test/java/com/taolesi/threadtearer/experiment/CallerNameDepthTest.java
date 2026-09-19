package com.taolesi.threadtearer.experiment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does {@code callerName()}'s StackWalker get more expensive on the deep
 * stacks the mixins actually run on?
 *
 * <p>The in-game watchdog caught the server thread inside
 * {@code StackFrameInfo.getMethodName()} / {@code expandStackFrameInfo}
 * during a stall, so this pins the cost against stack depth. If depth 60
 * costs tens of microseconds, it is the hot spot and the fix is to stop
 * walking the stack per call.
 */
class CallerNameDepthTest {

    /** Same shape as InteractionRelocator.callerName(). */
    private static String callerName() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> {
                    String cn = frame.getClassName();
                    int dot = cn.lastIndexOf('.');
                    String simple = dot < 0 ? cn : cn.substring(dot + 1);
                    int inner = simple.lastIndexOf('$');
                    String s = inner < 0 ? simple : simple.substring(inner + 1);
                    return s + "." + frame.getMethodName();
                })
                .filter(name -> !name.startsWith("InteractionRelocator."))
                .findFirst()
                .orElse("unknown"));
    }

    /** Recurses to a given depth, then runs the benchmark. */
    private static long benchAtDepth(int depth, int iters) {
        if (depth > 0) {
            return benchAtDepth(depth - 1, iters);
        }
        // Warm up this depth shape.
        for (int i = 0; i < 20_000; i++) {
            callerName();
        }
        long start = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < iters; i++) {
            sink += callerName().length();
        }
        long elapsed = System.nanoTime() - start;
        if (sink < 0) {
            throw new IllegalStateException();
        }
        return elapsed / iters;
    }

    @Test
    void stackWalkCostGrowsWithDepth() {
        int[] depths = {2, 8, 20, 40, 60, 90};
        long[] perCall = new long[depths.length];
        for (int i = 0; i < depths.length; i++) {
            perCall[i] = benchAtDepth(depths[i], 50_000);
        }
        System.out.println("[callerName] cost vs stack depth:");
        for (int i = 0; i < depths.length; i++) {
            System.out.printf("[callerName]   depth=%3d -> %6.2f µs/call%n",
                    depths[i], perCall[i] / 1000.0);
        }
        long shallow = perCall[0];
        long deep = perCall[depths.length - 1];
        System.out.printf("[callerName] deep/shallow ratio: %.1fx%n",
                deep / (double) shallow);
        System.out.printf("[callerName] => 1000 calls/tick at depth %d costs %.1f ms/tick%n",
                depths[depths.length - 1], deep / 1_000_000.0 * 1000);
        assertTrue(shallow > 0);
    }

    /**
     * Reference: what the same call costs when the name is a constant passed
     * in by the caller (the proposed fix).
     */
    @Test
    void constantNameCost() {
        final int iters = 5_000_000;
        AtomicLong sink = new AtomicLong();
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink.addAndGet("Level.setBlock".length());
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("[callerName] constant-name path: %.4f µs/call%n",
                elapsed / 1000.0 / iters);
        assertTrue(sink.get() > 0);
    }
}
