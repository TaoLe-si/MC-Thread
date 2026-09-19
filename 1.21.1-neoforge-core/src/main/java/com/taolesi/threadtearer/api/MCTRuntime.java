package com.taolesi.threadtearer.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Thread Tearer runtime facade.
 *
 * <p>Thread discipline (see docs/01-feasibility-analysis.md):
 * <ul>
 *   <li>Live world state is owned by the server thread; never touch it from
 *       {@link #submitCompute(ComputeTask)} workers.</li>
 *   <li>{@link #submitCompute(ComputeTask)} is for pure computation only.</li>
 *   <li>{@link #scheduleInteraction(InteractionTask)} is for deferrable,
 *       batchable interaction work (IO, serialization, bulk notifications),
 *       executed in FIFO order on a single dedicated thread.</li>
 *   <li>{@link #scheduleWorldInteraction(String, Runnable)} is how offloaded
 *       tick compute hands a live world write to that interaction thread; the
 *       write is FIFO-ordered with player interactions and applied on the
 *       server thread.</li>
 *   <li>{@link #snapshot(Object, long)} captures a versioned read-only view;
 *       results must be validated against the live state before commit.</li>
 *   <li>{@link #beginTransaction()} changes are applied only on the server
 *       thread via {@link Transaction#commit(Object)}.</li>
 * </ul>
 */
public interface MCTRuntime {

    /** Safe fallback used when Thread Tearer is not installed. */
    MCTRuntime NOOP = new Noop();

    /** Submits pure-compute work; runs on a compute pool thread. */
    <T> CompletableFuture<T> submitCompute(ComputeTask<T> task);

    /** Schedules deferrable interaction work on the single interaction thread (FIFO). */
    CompletableFuture<Void> scheduleInteraction(InteractionTask task);

    /**
     * Submits a world-mutating interaction from inside an offloaded tick
     * compute (a {@code submitCompute}/{@code stealTick} worker) to the single
     * interaction thread.
     *
     * <p>Addon mods call this when their tick computation produced a live
     * world write (place, insert, menu change, …). The write is FIFO-ordered
     * with player interactions and is finally applied on the server thread;
     * the caller must not wait on the returned future from the server
     * thread. Called on the server thread the write runs inline instead.
     *
     * @param name request label for stats/diagnostics
     * @param apply the live world write; runs on the server thread
     * @return outcome future completed after the owner thread applied (or
     *         skipped) the write; {@code null} never returned
     */
    CompletableFuture<OffloadOutcome> scheduleWorldInteraction(String name, Runnable apply);

    /**
     * Defers a live world write to the server thread's next tick-boundary
     * batch, without going through the interaction FIFO.
     *
     * <p>This is the primitive for the common case in an offloaded tick: the
     * compute found something that must happen on the owner thread, but the
     * write is independent of player input and only needs to happen soon.
     * Writes deferred this way are batched: a base with 200 machines pushing
     * into neighbours produces ONE server-thread task per tick, not 200. The
     * server thread never waits for compute; the compute worker hands the write
     * over and returns to its own work.
     *
     * <p>Use {@link #scheduleWorldInteraction(String, Runnable)} instead when
     * the write must be ordered against player interactions.
     *
     * @param apply the live world write; runs on the server thread
     */
    void deferWorldWrite(Runnable apply);

    /** Captures a versioned, read-only snapshot of a value. */
    <T> Snapshot<T> snapshot(T value, long version);

    /** Opens a new transaction for later server-thread commit or rollback. */
    <T> Transaction<T> beginTransaction();

    /**
     * Runs an optimistic workflow (Snapshot → Compute → Validate → Commit, retry on stale).
     *
     * <ol>
     *   <li>{@code snapshotFactory} runs on the server thread and reads live state;</li>
     *   <li>{@code compute} runs on a compute-pool thread and must be pure
     *       (no live state access, no hidden side effects);</li>
     *   <li>{@code validator} and {@code commit} run on the server thread;
     *       when validation fails the workflow retries up to {@code maxRetries}
     *       times, then completes exceptionally with {@link StaleSnapshotException}.</li>
     * </ol>
     *
     * <p><b>Threading contract:</b> never call {@link CompletableFuture#join()} on the
     * returned future from the server thread - the final stage is scheduled back onto
     * the server thread and would deadlock. Compose {@code whenComplete} callbacks
     * instead (as {@code /threadtearer demo run} does).
     */
    <T, R> CompletableFuture<R> optimistic(
            Supplier<Snapshot<T>> snapshotFactory,
            Function<Snapshot<T>, R> compute,
            BiPredicate<Snapshot<T>, R> validator,
            Consumer<R> commit,
            int maxRetries);

    /** Returns true when the current thread is the server thread. */
    boolean isServerThread();

    final class Noop implements MCTRuntime {
        @Override
        public <T> CompletableFuture<T> submitCompute(ComputeTask<T> task) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Thread Tearer runtime is not installed"));
        }

        @Override
        public CompletableFuture<Void> scheduleInteraction(InteractionTask task) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Thread Tearer runtime is not installed"));
        }

        @Override
        public CompletableFuture<OffloadOutcome> scheduleWorldInteraction(String name, Runnable apply) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Thread Tearer runtime is not installed"));
        }

        @Override
        public void deferWorldWrite(Runnable apply) {
            // The runtime is not installed, so there is no compute worker that
            // could have deferred this. Dropping it matches the caller's
            // contract (a missed deferral is better than an off-thread write).
        }

        @Override
        public <T> Snapshot<T> snapshot(T value, long version) {
            return new Snapshot<>() {
                @Override
                public T get() {
                    return value;
                }

                @Override
                public long version() {
                    return version;
                }
            };
        }

        @Override
        public <T> Transaction<T> beginTransaction() {
            throw new IllegalStateException("Thread Tearer runtime is not installed");
        }

        @Override
        public <T, R> CompletableFuture<R> optimistic(
                Supplier<Snapshot<T>> snapshotFactory,
                Function<Snapshot<T>, R> compute,
                BiPredicate<Snapshot<T>, R> validator,
                Consumer<R> commit,
                int maxRetries) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Thread Tearer runtime is not installed"));
        }

        @Override
        public boolean isServerThread() {
            return false;
        }
    }
}
