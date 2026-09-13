package com.taolesi.mcthread.experiment;

/**
 * Per-block-entity mutex so the vanilla ticker (compute pool) and extra
 * ticks (AE2 entity speed cards on the server thread) cannot mutate the
 * same machine at once. Different block entities still run in parallel.
 */
public interface BlockEntityTickLock {

    Object mcthread$tickLock();
}
