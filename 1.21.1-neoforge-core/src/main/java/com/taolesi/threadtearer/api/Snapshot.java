package com.taolesi.threadtearer.api;

/**
 * A versioned, read-only snapshot of a value (Snapshot Runtime primitive).
 *
 * <p>Created on the server thread, consumed by compute workers, and validated
 * against the live state version before any commit.
 */
public interface Snapshot<T> {

    /** Returns the immutable view. Never expose mutable live state through this. */
    T get();

    /** Version of the underlying state at snapshot time. */
    long version();

    /** Optimistic validation: is this snapshot still consistent with the expected version? */
    default boolean isValid(long expectedVersion) {
        return version() == expectedVersion;
    }
}
