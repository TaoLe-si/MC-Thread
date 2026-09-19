package com.taolesi.threadtearer.api;

/**
 * Pure computation work for {@link MCTRuntime#submitCompute(ComputeTask)}.
 *
 * <p>Contract: must not read or write live world state, must not have hidden
 * side effects (Constraint-001 in the research notes), and must be safe to
 * execute concurrently on any thread.
 */
@FunctionalInterface
public interface ComputeTask<T> {

    T compute() throws Exception;
}
