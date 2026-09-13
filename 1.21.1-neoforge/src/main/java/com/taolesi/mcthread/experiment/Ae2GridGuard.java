package com.taolesi.mcthread.experiment;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * AE2 grid HashMaps are not thread-safe. Wireless connectors merge grids on
 * the server thread while other tickers may still be in {@code Grid.add}.
 */
public final class Ae2GridGuard {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private Ae2GridGuard() {
    }

    public static boolean heldByCurrent() {
        return LOCK.isHeldByCurrentThread();
    }

    public static void acquire() {
        LOCK.lock();
    }

    public static void release() {
        if (LOCK.isHeldByCurrentThread()) {
            LOCK.unlock();
        }
    }

    public static void run(Runnable task) {
        acquire();
        try {
            task.run();
        } finally {
            release();
        }
    }

    public static <T> T call(Supplier<T> task) {
        LOCK.lock();
        try {
            return task.get();
        } finally {
            LOCK.unlock();
        }
    }
}
