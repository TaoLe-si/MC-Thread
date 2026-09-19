package com.taolesi.threadtearer.runtime;

import com.taolesi.threadtearer.api.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Default transaction implementation with write-on-commit and partial-update
 * rollback (Law-004 / ADR-029/030).
 */
public final class TransactionImpl<T> implements Transaction<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Transaction");

    private record ChangeRecord<T>(Consumer<T> apply, Consumer<T> undo) {
    }

    private enum State {
        OPEN, COMMITTED, ROLLED_BACK
    }

    private final List<ChangeRecord<T>> changes = new ArrayList<>();
    private final BooleanSupplier serverThreadCheck;
    private State state = State.OPEN;

    public TransactionImpl(BooleanSupplier serverThreadCheck) {
        this.serverThreadCheck = serverThreadCheck;
    }

    @Override
    public void addChange(Consumer<T> apply, Consumer<T> undo) {
        requireOpen();
        changes.add(new ChangeRecord<>(apply, undo));
    }

    @Override
    public int changeCount() {
        return changes.size();
    }

    @Override
    public T commit(T target) {
        requireOpen();
        if (!serverThreadCheck.getAsBoolean()) {
            throw new IllegalStateException(
                    "Transaction.commit must be executed on the server thread");
        }
        int applied = 0;
        try {
            for (ChangeRecord<T> change : changes) {
                change.apply().accept(target);
                applied++;
            }
        } catch (RuntimeException | Error t) {
            for (int i = applied - 1; i >= 0; i--) {
                try {
                    changes.get(i).undo().accept(target);
                } catch (Throwable ignored) {
                    // rollback must not mask the original failure
                }
            }
            state = State.ROLLED_BACK;
            LOGGER.warn("transaction rolled back after {} of {} changes applied: {}",
                    applied, changes.size(), t.toString());
            throw t;
        }
        state = State.COMMITTED;
        return target;
    }

    @Override
    public void rollback() {
        requireOpen();
        state = State.ROLLED_BACK;
    }

    @Override
    public boolean isTerminated() {
        return state != State.OPEN;
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Transaction is already " + state.name().toLowerCase());
        }
    }
}
