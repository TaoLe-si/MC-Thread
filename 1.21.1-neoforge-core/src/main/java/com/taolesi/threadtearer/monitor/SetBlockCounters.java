package com.taolesi.threadtearer.monitor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the {@code Level.setBlock} no-op short-circuit in
 * {@code LevelMixin}. Kept OUT of the mixin class itself: mixin classes may
 * only contain private static methods (non-private statics fail the mixin
 * applicator with {@code InvalidMixinException}), so the counters and their
 * accessors live in this plain class and the mixin calls into them.
 */
public final class SetBlockCounters {

    private static final AtomicLong REAL = new AtomicLong();
    private static final AtomicLong NO_OP = new AtomicLong();

    private SetBlockCounters() {
    }

    public static void recordReal() {
        REAL.incrementAndGet();
    }

    public static void recordNoOp() {
        NO_OP.incrementAndGet();
    }

    public static long real() {
        return REAL.get();
    }

    public static long noOp() {
        return NO_OP.get();
    }

    public static void reset() {
        REAL.set(0);
        NO_OP.set(0);
    }
}