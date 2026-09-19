package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.Snapshot;
import com.taolesi.threadtearer.api.StaleSnapshotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Optimistic concurrency workflow (Snapshot → Compute → Validate → Commit/Retry).
 *
 * <p>Pure Java: executors are injected, so the retry semantics can be unit
 * tested without a game instance. The server executor runs snapshot/validate/
 * commit; the compute executor runs pure compute.
 */
public final class OptimisticRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Optimistic");

    private record Result<T, R>(Snapshot<T> snapshot, R value) {
    }

    private final Executor serverExecutor;
    private final Executor computeExecutor;

    public OptimisticRunner(Executor serverExecutor, Executor computeExecutor) {
        this.serverExecutor = serverExecutor;
        this.computeExecutor = computeExecutor;
    }

    public <T, R> CompletableFuture<R> run(
            Supplier<Snapshot<T>> snapshotFactory,
            Function<Snapshot<T>, R> compute,
            BiPredicate<Snapshot<T>, R> validator,
            Consumer<R> commit,
            int maxRetries) {
        return attempt(snapshotFactory, compute, validator, commit, Math.max(0, maxRetries));
    }

    private <T, R> CompletableFuture<R> attempt(
            Supplier<Snapshot<T>> snapshotFactory,
            Function<Snapshot<T>, R> compute,
            BiPredicate<Snapshot<T>, R> validator,
            Consumer<R> commit,
            int retriesLeft) {
        CompletableFuture<Snapshot<T>> snapshotStage =
                CompletableFuture.supplyAsync(snapshotFactory, serverExecutor);
        CompletableFuture<Result<T, R>> computeStage = snapshotStage.thenCompose(snapshot ->
                CompletableFuture.supplyAsync(() -> new Result<>(snapshot, compute.apply(snapshot)),
                        computeExecutor));
        return computeStage.thenComposeAsync(result -> {
            if (validator.test(result.snapshot(), result.value())) {
                commit.accept(result.value());
                return CompletableFuture.completedFuture(result.value());
            }
            if (retriesLeft <= 0) {
                LOGGER.warn("optimistic workflow exhausted retries (snapshot version={}, value={})",
                        result.snapshot().version(), result.value());
                return CompletableFuture.failedFuture(new StaleSnapshotException());
            }
            LOGGER.info("optimistic retry ({} left) after stale snapshot version={}",
                    retriesLeft, result.snapshot().version());
            return attempt(snapshotFactory, compute, validator, commit, retriesLeft - 1);
        }, serverExecutor);
    }
}
