package com.taolesi.mcthread.experiment.till;

/**
 * Interaction-thread result for a till request.
 *
 * @param resultBlockId registry id to place when {@code apply} is true; {@code null} otherwise
 */
public record TillDecision(boolean apply, String resultBlockId) {

    public static TillDecision pass() {
        return new TillDecision(false, null);
    }

    public static TillDecision till(String resultBlockId) {
        return new TillDecision(true, resultBlockId);
    }
}
