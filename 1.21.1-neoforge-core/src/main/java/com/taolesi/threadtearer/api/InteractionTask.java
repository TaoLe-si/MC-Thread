package com.taolesi.threadtearer.api;

/**
 * Deferrable interaction work for {@link MCTRuntime#scheduleInteraction(InteractionTask)}.
 *
 * <p>Contract: must not modify live world state. Typical use: asynchronous IO,
 * serialization, batched notifications. Executed in FIFO order on the single
 * interaction thread.
 */
@FunctionalInterface
public interface InteractionTask {

    void run() throws Exception;
}
