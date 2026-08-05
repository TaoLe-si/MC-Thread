package com.taolesi.mcthread.runtime;

import com.taolesi.mcthread.api.DomainAdapter;
import com.taolesi.mcthread.api.MCTRuntime;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Registry and lifecycle manager for {@link DomainAdapter}s.
 *
 * <p>An adapter is attached only when its target mod is loaded; no target mod
 * means always available. Refresh should be called at mod load and server start.
 */
public final class AdapterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Adapters");

    private final MCTRuntime runtime;
    private final Predicate<String> modLoaded;
    private final Map<String, DomainAdapter> registered = new ConcurrentHashMap<>();
    private final Map<String, DomainAdapter> attached = new ConcurrentHashMap<>();

    public AdapterRegistry(MCTRuntime runtime) {
        this(runtime, AdapterRegistry::defaultModLoaded);
    }

    /** Testable constructor with an injected mod-availability predicate. */
    public AdapterRegistry(MCTRuntime runtime, Predicate<String> modLoaded) {
        this.runtime = runtime;
        this.modLoaded = modLoaded;
    }

    public boolean register(DomainAdapter adapter) {
        if (adapter == null || adapter.domainId() == null || adapter.domainId().isBlank()) {
            return false;
        }
        return registered.putIfAbsent(adapter.domainId(), adapter) == null;
    }

    /** Attaches newly available adapters and detaches unavailable ones. */
    public void refresh() {
        for (DomainAdapter adapter : new ArrayList<>(registered.values())) {
            boolean available = isAvailable(adapter);
            boolean isAttached = attached.containsKey(adapter.domainId());
            if (available && !isAttached) {
                attached.put(adapter.domainId(), adapter);
                adapter.onAttach(runtime);
                LOGGER.info("adapter attached: {} (target={})", adapter.domainId(), adapter.targetModId());
            } else if (!available && isAttached) {
                attached.remove(adapter.domainId());
                adapter.onDetach();
                LOGGER.info("adapter detached: {} (target={})", adapter.domainId(), adapter.targetModId());
            }
        }
    }

    public boolean isAvailable(DomainAdapter adapter) {
        String target = adapter.targetModId();
        if (target == null || target.isBlank()) {
            return true;
        }
        try {
            return modLoaded.test(target);
        } catch (Throwable t) {
            return false;
        }
    }

    public Optional<DomainAdapter> byDomain(String domainId) {
        return Optional.ofNullable(registered.get(domainId));
    }

    public List<DomainAdapter> registeredAdapters() {
        return registered.values().stream()
                .sorted(Comparator.comparing(DomainAdapter::domainId))
                .toList();
    }

    public List<DomainAdapter> attachedAdapters() {
        return attached.values().stream()
                .sorted(Comparator.comparing(DomainAdapter::domainId))
                .toList();
    }

    private static boolean defaultModLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }
}
