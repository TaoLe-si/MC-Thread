package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.InteractionTask;
import com.taolesi.mcthread.experiment.InteractionRelocator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-thread, FIFO executor for player-facing interaction work.
 *
 * <p>World ticks compute on {@link ComputePool}. Live world writes found during
 * that compute are queued here and applied on the owner thread.
 */
public final class InteractionExecutor implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("MCT-Interaction"));

    public CompletableFuture<Void> schedule(InteractionTask task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, executor);
    }

    /** Exposes the underlying single-thread executor for experimental offload pipelines. */
    public Executor asExecutor() {
        return command -> executor.execute(() -> {
            InteractionRelocator.enterInteractionThread();
            try {
                command.run();
            } finally {
                InteractionRelocator.leaveInteractionThread();
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
