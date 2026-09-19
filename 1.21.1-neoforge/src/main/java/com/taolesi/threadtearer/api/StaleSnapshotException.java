package com.taolesi.threadtearer.api;

/** Thrown when an optimistic workflow exhausts its retry budget on stale snapshots. */
public final class StaleSnapshotException extends RuntimeException {

    public StaleSnapshotException() {
        this("snapshot is stale; optimistic retry budget exhausted");
    }

    public StaleSnapshotException(String message) {
        super(message);
    }
}
