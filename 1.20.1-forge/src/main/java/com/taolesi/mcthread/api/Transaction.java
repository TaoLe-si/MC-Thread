package com.taolesi.mcthread.api;

import java.util.function.Consumer;

/**
 * A transaction over a mutable target (Transaction Runtime primitive).
 *
 * <p>Changes are recorded first (write-on-commit). {@link #commit(Object)}
 * applies them on the server thread; if any change fails, already-applied
 * changes are rolled back so no partial update survives. {@link #rollback()}
 * discards the recorded changes.
 */
public interface Transaction<T> {

    /** Records a change: {@code apply} mutates the target, {@code undo} reverts it. */
    void addChange(Consumer<T> apply, Consumer<T> undo);

    /** Number of recorded changes. */
    int changeCount();

    /**
     * Applies all recorded changes to {@code target} on the server thread.
     * On failure, undoes already-applied changes and rethrows.
     */
    T commit(T target);

    /** Discards recorded changes without applying them. */
    void rollback();

    /** True once committed or rolled back. */
    boolean isTerminated();
}
