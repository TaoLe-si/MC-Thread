package com.taolesi.mcthread.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputePoolTest {

    @Test
    void computesValue() throws Exception {
        try (ComputePool pool = new ComputePool(2)) {
            assertEquals(42, pool.submit(() -> 40 + 2).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void propagatesFailure() {
        try (ComputePool pool = new ComputePool(2)) {
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> pool.submit(() -> {
                        throw new IllegalStateException("boom");
                    }).join());
            assertInstanceOf(IllegalStateException.class, ex.getCause());
        }
    }

    @Test
    void threadsAreDaemonAndNamed() throws Exception {
        try (ComputePool pool = new ComputePool(1)) {
            AtomicReference<String> name = new AtomicReference<>();
            AtomicReference<Boolean> daemon = new AtomicReference<>();
            pool.submit(() -> {
                name.set(Thread.currentThread().getName());
                daemon.set(Thread.currentThread().isDaemon());
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertTrue(name.get().startsWith("MCT-Compute-"), "unexpected thread name: " + name.get());
            assertTrue(daemon.get(), "compute threads must be daemon");
        }
    }
}
