package com.taolesi.threadtearer.monitor;

import java.util.concurrent.atomic.LongAdder;

/**
 * Server-thread phase timings. Answers "which phase eats the tick" from a
 * single in-game run, without a sampling profiler: the monitor line prints
 * total ms and call count per phase over the logging interval.
 *
 * <p>Counters are drained (reset) on each monitor line so the numbers are
 * per-interval. Kept in a plain class — mixin classes may not hold
 * non-private static members.
 */
public final class PhaseTimings {

    /** {@code InteractionRelocator.steal} calls that got past the cheap gates. */
    public static final LongAdder STEAL_CALLS = new LongAdder();
    public static final LongAdder STEAL_NANOS = new LongAdder();

    /** {@code InteractionRelocator.runApply} — the owner-thread apply step. */
    public static final LongAdder APPLY_CALLS = new LongAdder();
    public static final LongAdder APPLY_NANOS = new LongAdder();

    /** {@code WriteCoalescer.flush} — off-thread writes drained per tick. */
    public static final LongAdder FLUSH_CALLS = new LongAdder();
    public static final LongAdder FLUSH_NANOS = new LongAdder();
    public static final LongAdder FLUSH_WRITES = new LongAdder();

    /**
     * Writes an offloaded tick handed back to the server thread instead of
     * running itself, via {@code MCTRuntime.deferWorldWrite}. Addons use this
     * for the part of a tick that must not run on a worker. These land in the
     * same per-tick batch as the compute writes, so they are included in
     * {@link #FLUSH_WRITES}; this counter is what distinguishes them.
     */
    public static final LongAdder DEFER_CALLS = new LongAdder();

    /**
     * {@code PositionUpdateBatch.flush} — positions whose deferred neighbour /
     * comparator / unsaved bookkeeping ran at the tick boundary.
     */
    public static final LongAdder NEIGHBOR_POSITIONS = new LongAdder();
    public static final LongAdder NEIGHBOR_NANOS = new LongAdder();

    /**
     * Raw {@code PositionUpdateBatch.record} calls, i.e. bookkeeping a compute
     * worker asked for. Divided by {@link #NEIGHBOR_POSITIONS} this is the
     * collapse ratio: a loaded Titanium base asked for ~7 per offloaded tick
     * before the batch existed, and each one was its own deferred apply task.
     */
    public static final LongAdder POSITION_UPDATES = new LongAdder();

    /**
     * Time a thread spends BLOCKED waiting for a per-BE lock. A large value
     * here means the server thread is stalling on compute workers, which is
     * what produces multi-second "Can't keep up" gaps rather than a
     * uniformly slow tick.
     */
    public static final LongAdder LOCK_WAITS = new LongAdder();
    public static final LongAdder LOCK_WAIT_NANOS = new LongAdder();

    private PhaseTimings() {
    }

    /** @return one log-ready line; resets every counter. */
    public static String drainAndFormat() {
        long sc = STEAL_CALLS.sumThenReset();
        long sn = STEAL_NANOS.sumThenReset();
        long ac = APPLY_CALLS.sumThenReset();
        long an = APPLY_NANOS.sumThenReset();
        long fc = FLUSH_CALLS.sumThenReset();
        long fn = FLUSH_NANOS.sumThenReset();
        long fw = FLUSH_WRITES.sumThenReset();
        long np = NEIGHBOR_POSITIONS.sumThenReset();
        long nn = NEIGHBOR_NANOS.sumThenReset();
        long pu = POSITION_UPDATES.sumThenReset();
        long lw = LOCK_WAITS.sumThenReset();
        long ln = LOCK_WAIT_NANOS.sumThenReset();
        long dc = DEFER_CALLS.sumThenReset();
        return String.format(
                "phases[ms/calls] steal=%.2f/%d apply=%.2f/%d flush=%.2f/%d(%d writes, %d deferred)"
                        + " nb=%.2f/%d(%d recorded) lockwait=%.2f/%d",
                ms(sn), sc, ms(an), ac, ms(fn), fc, fw, dc, ms(nn), np, pu, ms(ln), lw);
    }

    /** Same numbers, but without resetting (for logging alongside a drain). */
    public static String peekAndFormat() {
        return String.format(
                "phases[ms/calls] steal=%.2f/%d apply=%.2f/%d flush=%.2f/%d(%d writes, %d deferred)"
                        + " nb=%.2f/%d(%d recorded) lockwait=%.2f/%d",
                ms(STEAL_NANOS.sum()), STEAL_CALLS.sum(),
                ms(APPLY_NANOS.sum()), APPLY_CALLS.sum(),
                ms(FLUSH_NANOS.sum()), FLUSH_CALLS.sum(),
                FLUSH_WRITES.sum(), DEFER_CALLS.sum(),
                ms(NEIGHBOR_NANOS.sum()), NEIGHBOR_POSITIONS.sum(), POSITION_UPDATES.sum(),
                ms(LOCK_WAIT_NANOS.sum()), LOCK_WAITS.sum());
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
