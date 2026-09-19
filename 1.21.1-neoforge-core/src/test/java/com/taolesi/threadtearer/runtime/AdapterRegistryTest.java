package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.DomainAdapter;
import com.taolesi.threadtearer.api.MCTRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterRegistryTest {

    private static final class FakeAdapter implements DomainAdapter {
        private final String id;
        private final String target;
        private final AtomicBoolean attached = new AtomicBoolean();

        FakeAdapter(String id, String target) {
            this.id = id;
            this.target = target;
        }

        @Override
        public String domainId() {
            return id;
        }

        @Override
        public String targetModId() {
            return target;
        }

        @Override
        public void onAttach(MCTRuntime runtime) {
            attached.set(true);
        }

        @Override
        public void onDetach() {
            attached.set(false);
        }
    }

    @Test
    void registersAndFinds() {
        AdapterRegistry registry = new AdapterRegistry(MCTRuntime.NOOP, modId -> false);
        FakeAdapter adapter = new FakeAdapter("always", "");
        assertTrue(registry.register(adapter));
        assertFalse(registry.register(adapter), "duplicate domain id must be rejected");
        assertEquals(adapter, registry.byDomain("always").orElseThrow());
    }

    @Test
    void attachAndDetachFollowTargetModAvailability() {
        AtomicReference<String> loaded = new AtomicReference<>("ae2");
        AdapterRegistry registry = new AdapterRegistry(MCTRuntime.NOOP, modId -> modId.equals(loaded.get()));

        FakeAdapter always = new FakeAdapter("always", "");
        FakeAdapter ae2 = new FakeAdapter("ae2-x", "ae2");
        FakeAdapter rs = new FakeAdapter("rs-x", "refinedstorage");
        registry.register(always);
        registry.register(ae2);
        registry.register(rs);

        registry.refresh();
        assertTrue(always.attached.get());
        assertTrue(ae2.attached.get());
        assertFalse(rs.attached.get());

        loaded.set("refinedstorage");
        registry.refresh();
        assertTrue(always.attached.get());
        assertFalse(ae2.attached.get(), "adapter must detach when its target mod unloads");
        assertTrue(rs.attached.get(), "adapter must attach when its target mod loads");
    }
}
