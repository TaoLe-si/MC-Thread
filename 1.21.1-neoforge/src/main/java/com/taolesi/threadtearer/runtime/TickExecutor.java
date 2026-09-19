package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.experiment.InteractionRelocator;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-thread FIFO for tick <em>compute</em>. Live writes still run on the
 * owner thread during apply. This is not the parallel {@link ComputePool}.
 */
public final class TickExecutor implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory("MCT-Tick"));

    public Executor asExecutor() {
        return command -> executor.execute(() -> {
            InteractionRelocator.enterTickThread();
            try {
                command.run();
            } finally {
                InteractionRelocator.leaveTickThread();
            }
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
