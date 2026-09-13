package com.taolesi.mcthread.experiment;

/**
 * Result of one offloaded interaction.
 *
 * <p>This is the experiment's analogue of "interaction thread produced a result,
 * owner thread updated the world (or refused to)".
 */
public enum OffloadOutcome {
    /** Decision applied to live state on the owner (server) thread. */
    APPLIED,
    /** Decision was a no-op; live state was not written. */
    PASSED,
    /** Live state drifted from the snapshot; apply was skipped. */
    REJECTED_STALE,
    /** Decide/validate/apply threw; live state was not written by this pipeline. */
    FAILED
}
