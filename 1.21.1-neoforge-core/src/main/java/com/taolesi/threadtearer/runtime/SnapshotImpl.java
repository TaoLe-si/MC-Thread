package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.Snapshot;

/** Immutable, versioned snapshot. */
public final class SnapshotImpl<T> implements Snapshot<T> {

    private final T value;
    private final long version;

    public SnapshotImpl(T value, long version) {
        this.value = value;
        this.version = version;
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "SnapshotImpl{version=" + version + ", value=" + value + "}";
    }
}
