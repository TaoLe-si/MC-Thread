package com.taolesi.mcthread.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest {

    @Test
    void commitAppliesInOrder() {
        TransactionImpl<int[]> tx = new TransactionImpl<>(() -> true);
        int[] target = {0};
        tx.addChange(t -> t[0] += 1, t -> t[0] -= 1);
        tx.addChange(t -> t[0] *= 10, t -> t[0] /= 10);
        tx.commit(target);
        assertEquals(10, target[0]);
        assertTrue(tx.isTerminated());
    }

    @Test
    void commitRejectedOffServerThread() {
        TransactionImpl<int[]> tx = new TransactionImpl<>(() -> false);
        tx.addChange(t -> t[0] += 1, t -> t[0] -= 1);
        assertThrows(IllegalStateException.class, () -> tx.commit(new int[1]));
        assertFalse(tx.isTerminated(), "failed commit must leave the transaction open");
    }

    @Test
    void rollbackDiscardsChanges() {
        TransactionImpl<int[]> tx = new TransactionImpl<>(() -> true);
        tx.addChange(t -> t[0] += 1, t -> t[0] -= 1);
        tx.rollback();
        assertTrue(tx.isTerminated());
        assertThrows(IllegalStateException.class, () -> tx.commit(new int[1]));
    }

    @Test
    void partialFailureRollsBackAppliedChanges() {
        TransactionImpl<int[]> tx = new TransactionImpl<>(() -> true);
        int[] target = {0};
        tx.addChange(t -> t[0] += 5, t -> t[0] -= 5);
        tx.addChange(t -> {
            throw new IllegalStateException("boom");
        }, t -> {
        });
        assertThrows(IllegalStateException.class, () -> tx.commit(target));
        assertEquals(0, target[0], "applied changes must be undone on failure");
        assertTrue(tx.isTerminated());
    }

    @Test
    void addChangeAfterTerminationRejected() {
        TransactionImpl<int[]> tx = new TransactionImpl<>(() -> true);
        tx.rollback();
        assertThrows(IllegalStateException.class, () -> tx.addChange(t -> {
        }, t -> {
        }));
    }
}
