package com.taolesi.mcthread.experiment;

/**
 * One offloadable interaction type.
 *
 * <p>Thread contract:
 * <ul>
 *   <li>{@link #decide(Object)} runs on the interaction thread and must use only
 *       the snapshot — never live world objects.</li>
 *   <li>{@link #validate(Object, Object)} and {@link #apply(Object, Object)} run
 *       on the owner (server) thread and are the only methods allowed to touch
 *       live state.</li>
 * </ul>
 */
public interface OffloadSession<S, D> {

    /** Pure decision from a frozen request snapshot. */
    D decide(S snapshot);

    /** {@code true} when {@code decision} should mutate live state. */
    boolean shouldApply(D decision);

    /** Re-reads live state; {@code false} means the snapshot is stale. */
    boolean validate(S snapshot, D decision);

    /** Applies the decision to live state. Owner thread only. */
    void apply(S snapshot, D decision);
}
