package com.taolesi.mcthread.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The MC Thread runtime facade.
 *
 * <p>Thread discipline (see docs/01-feasibility-analysis.md):
 * <ul>
 *   <li>Live world state is owned by the server thread; never touch it from
 *       {@link #submitCompute(ComputeTask)} workers.</li>
 *   <li>{@link #submitCompute(ComputeTask)} is for pure computation only.</li>
 *   <li>{@link #scheduleInteraction(InteractionTask)} is for deferrable,
 *       batchable interaction work (IO, serialization, bulk notifications),
 *       executed in FIFO order on a single dedicated thread.</li>
 *   <li>{@link #snapshot(Object, long)} captures a versioned read-only view;
 *       results must be validated against the live state before commit.</li>
 *   <li>{@link #beginTransaction()} changes are applied only on the server
 *       thread via {@link Transaction#commit(Object)}.</li>
 * </ul>
 */
public interface MCTRuntime {

    /** Safe fallback used when MC Thread is not installed. */
    MCTRuntime NOOP = new Noop();

    /** Submits pure-compute work; runs on a compute pool thread. */
    <T> CompletableFuture<T> submitCompute(ComputeTask<T> task);

    /** Schedules deferrable interaction work on the single interaction thread (FIFO). */
    CompletableFuture<Void> scheduleInteraction(InteractionTask task);

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
     * instead (as {@code /mcthread demo run} does).
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
                    new IllegalStateException("MC Thread runtime is not installed"));
        }

        @Override
        public CompletableFuture<Void> scheduleInteraction(InteractionTask task) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MC Thread runtime is not installed"));
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
            throw new IllegalStateException("MC Thread runtime is not installed");
        }

        @Override
        public <T, R> CompletableFuture<R> optimistic(
                Supplier<Snapshot<T>> snapshotFactory,
                Function<Snapshot<T>, R> compute,
                BiPredicate<Snapshot<T>, R> validator,
                Consumer<R> commit,
                int maxRetries) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MC Thread runtime is not installed"));
        }

        @Override
        public boolean isServerThread() {
            return false;
        }
    }
}
