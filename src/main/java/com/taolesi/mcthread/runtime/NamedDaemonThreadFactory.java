package com.taolesi.mcthread.runtime;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Daemon thread factory with a stable prefix for diagnostics. */
final class NamedDaemonThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    NamedDaemonThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}
