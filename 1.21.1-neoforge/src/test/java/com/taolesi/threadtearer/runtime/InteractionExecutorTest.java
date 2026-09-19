package com.taolesi.threadtearer.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionExecutorTest {

    @Test
    void fifoOrder() throws Exception {
        try (InteractionExecutor executor = new InteractionExecutor()) {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            executor.schedule(() -> order.add("a")).get(5, TimeUnit.SECONDS);
            executor.schedule(() -> order.add("b")).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("a", "b"), order);
        }
    }

    @Test
    void failureDoesNotKillExecutor() throws Exception {
        try (InteractionExecutor executor = new InteractionExecutor()) {
            assertThrows(CompletionException.class,
                    () -> executor.schedule(() -> {
                        throw new IllegalStateException("boom");
                    }).join());
            AtomicBoolean ran = new AtomicBoolean(false);
            executor.schedule(() -> ran.set(true)).get(5, TimeUnit.SECONDS);
            assertTrue(ran.get(), "executor must survive task failures");
        }
    }
}
