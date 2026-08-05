package com.taolesi.mcthread.api;

/**
 * Static entry point for other mods to access the MC Thread runtime.
 *
 * <p>If MC Thread is not present (or not loaded), {@link #runtime()} returns a
 * safe no-op implementation, so third-party mods can compile against this API
 * without a hard dependency.
 */
public final class MCT {

    private static volatile MCTRuntime runtime = MCTRuntime.NOOP;

    private MCT() {
    }

    /** Returns the active MC Thread runtime, or a safe no-op if unavailable. */
    public static MCTRuntime runtime() {
        return runtime;
    }

    /**
     * Installs the MC Thread runtime implementation.
     *
     * @apiNote internal use only; called by MC Thread during mod construction.
     */
    public static void install(final MCTRuntime impl) {
        if (impl != null) {
            runtime = impl;
        }
    }
}
