package com.taolesi.threadtearer.api;

/**
 * A domain-specific optimization adapter (ADR-032: Adapter 优先于 Universal VM).
 *
 * <p>Each adapter owns one domain (e.g. craft planning, storage query) and is
 * attached only when its target mod is loaded. Adapters must be pure add-ons:
 * no hard dependency, no effect when unavailable.
 */
public interface DomainAdapter {

    /** Stable domain identifier, e.g. {@code "ae2-craft-planning"}. */
    String domainId();

    /** Target mod id; empty string means the domain has no external dependency. */
    String targetModId();

    /** Called by the registry when the adapter becomes available. */
    default void onAttach(MCTRuntime runtime) {
    }

    /** Called by the registry when the adapter becomes unavailable. */
    default void onDetach() {
    }
}
