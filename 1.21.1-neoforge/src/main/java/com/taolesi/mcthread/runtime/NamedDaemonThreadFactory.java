package com.taolesi.mcthread.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Daemon thread factory with a stable prefix for diagnostics. */
final class NamedDaemonThreadFactory implements ThreadFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread");

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    NamedDaemonThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        String name = prefix + "-" + counter.incrementAndGet();
        Thread t = new Thread(() -> {
            LOGGER.info("{} started", name);
            r.run();
        }, name);
        t.setDaemon(true);
        return t;
    }
}
