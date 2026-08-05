package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.InteractionTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-thread, FIFO executor for deferrable interaction work.
 *
 * <p>Serialization guarantees ordering without locks; this is the "one
 * interaction thread" component of the runtime. Tasks must not modify live
 * world state.
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

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
