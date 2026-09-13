package com.taolesi.mcthread.experiment.till;

import java.util.UUID;

/**
 * Frozen player-block interaction request.
 *
 * <p>Built on the server thread at intercept time. The interaction thread may
 * read this object but must not follow any live world reference through it —
 * every field is a value copy (ids, ints, flags).
 */
public record TillSnapshot(
        long capturedAtNanos,
        String dimension,
        int x,
        int y,
        int z,
        String blockId,
        boolean airAbove,
        String itemId,
        boolean canHoeTill,
        boolean mainHand,
        boolean creative,
        UUID playerId) {
}
