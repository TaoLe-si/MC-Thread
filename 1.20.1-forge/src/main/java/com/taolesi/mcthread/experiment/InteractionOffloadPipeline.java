package com.taolesi.mcthread.experiment;

import com.taolesi.mcthread.runtime.InteractionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Request/result snapshot pipeline plus the place/use round-trip:
 * A requests → interaction computes → apply-request to owner → owner writes
 * live state → follow-up update request → interaction computes → owner updates.
 * The owner thread never waits.
 */
public final class InteractionOffloadPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Experiment");

    private final Executor ownerExecutor;
    private final Executor interactionExecutor;
    private final Executor tickExecutor;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong rejectedStale = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong decideNanos = new AtomicLong();
    private final AtomicLong applyNanos = new AtomicLong();
    private final AtomicReference<Throwable> lastError = new AtomicReference<>();
    private final AtomicReference<OffloadOutcome> lastOutcome = new AtomicReference<>();
    private final AtomicReference<String> lastEmitThread = new AtomicReference<>();

    public InteractionOffloadPipeline(Executor ownerExecutor, Executor interactionExecutor) {
        this(ownerExecutor, interactionExecutor, interactionExecutor);
    }

    public InteractionOffloadPipeline(Executor ownerExecutor, Executor interactionExecutor,
                                      Executor tickExecutor) {
        this.ownerExecutor = ownerExecutor;
        this.interactionExecutor = interactionExecutor;
        this.tickExecutor = tickExecutor;
    }

    /**
     * A → interaction compute → apply-request to owner. The owner never waits.
     * Live vanilla runs only in {@link InteractionRelocator#runApply}.
     */
    public void relocateVanilla(Runnable vanilla) {
        relocateVanilla("relocateVanilla", vanilla);
    }

    public void relocateVanilla(String request, Runnable vanilla) {
        submitted.incrementAndGet();
        interactionExecutor.execute(new InteractionRequest(request, () -> computeThenApply(request, vanilla)));
    }

    /**
     * Block-entity tick: the compute pool runs the ticker. World writes inside
     * it are stolen to the interaction FIFO. No tick logs. The owner does not
     * re-run the ticker.
     */
    public void relocateTick(Runnable vanilla) {
        relocateTick("relocateTick", vanilla);
    }

    public void relocateTick(String request, Runnable vanilla) {
        submitted.incrementAndGet();
        tickExecutor.execute(() -> runBlockEntityTick(request, vanilla));
    }

    private void runBlockEntityTick(String request, Runnable vanilla) {
        long start = System.nanoTime();
        try {
            InteractionRelocator.enterCompute();
            lastEmitThread.set(Thread.currentThread().getName());
            vanilla.run();
            emitted.incrementAndGet();
            applied.incrementAndGet();
            lastOutcome.set(OffloadOutcome.APPLIED);
        } catch (Throwable t) {
            failed.incrementAndGet();
            lastError.set(t);
            lastOutcome.set(OffloadOutcome.FAILED);
        } finally {
            InteractionRelocator.leaveCompute();
            decideNanos.addAndGet(System.nanoTime() - start);
        }
    }

    private void computeThenApply(String request, Runnable vanilla) {
        long start = System.nanoTime();
        try {
            InteractionRelocator.enterCompute();
        } catch (Throwable t) {
            failed.incrementAndGet();
            lastError.set(t);
            lastOutcome.set(OffloadOutcome.FAILED);
            LOGGER.warn("interaction compute failed", t);
            return;
        } finally {
            InteractionRelocator.leaveCompute();
            decideNanos.addAndGet(System.nanoTime() - start);
        }
        lastEmitThread.set(Thread.currentThread().getName());
        emitted.incrementAndGet();
        ownerExecutor.execute(() -> applyOnOwner(request, vanilla));
    }

    private void applyOnOwner(String request, Runnable vanilla) {
        long start = System.nanoTime();
        try {
            InteractionRelocator.runApply(request, vanilla);
            applyNanos.addAndGet(System.nanoTime() - start);
            applied.incrementAndGet();
            lastOutcome.set(OffloadOutcome.APPLIED);
        } catch (Throwable t) {
            applyNanos.addAndGet(System.nanoTime() - start);
            failed.incrementAndGet();
            lastError.set(t);
            lastOutcome.set(OffloadOutcome.FAILED);
            if (!request.toLowerCase().contains("tick") && !request.toLowerCase().contains("passenger")) {
                LOGGER.warn("interaction apply failed", t);
            }
        }
    }

    /**
     * Enqueue a request. Returns immediately; the caller must not wait for the
     * decision or the world write.
     */
    public <S, D> void submit(S snapshot, OffloadSession<S, D> session) {
        submit(snapshot, session, null);
    }

    /**
     * Same as {@link #submit(Object, OffloadSession)} but completes {@code applied}
     * after the owner thread consumes the emitted result. Never join that future
     * from the owner thread.
     */
    public <S, D> void submit(S snapshot, OffloadSession<S, D> session,
                              CompletableFuture<OffloadOutcome> applied) {
        submitted.incrementAndGet();
        String name = session.getClass().getSimpleName() + " " + snapshot.getClass().getSimpleName();
        interactionExecutor.execute(new InteractionRequest(name,
                () -> decideAndEmit(snapshot, session, applied)));
    }

    private <S, D> void decideAndEmit(S snapshot, OffloadSession<S, D> session,
                                      CompletableFuture<OffloadOutcome> applied) {
        D decision = null;
        Throwable error = null;
        long start = System.nanoTime();
        try {
            decision = session.decide(snapshot);
        } catch (Throwable t) {
            error = t;
        } finally {
            decideNanos.addAndGet(System.nanoTime() - start);
        }
        emit(new OffloadMessage<>(snapshot, session, decision, error, applied));
    }

    /**
     * Interaction thread sends the computed result. Returns without waiting for
     * the owner to consume it.
     */
    private <S, D> void emit(OffloadMessage<S, D> message) {
        lastEmitThread.set(Thread.currentThread().getName());
        emitted.incrementAndGet();
        ownerExecutor.execute(() -> consume(message));
    }

    private <S, D> void consume(OffloadMessage<S, D> message) {
        OffloadOutcome outcome;
        if (message.error != null) {
            failed.incrementAndGet();
            lastError.set(message.error);
            LOGGER.warn("interaction offload decide failed", message.error);
            outcome = OffloadOutcome.FAILED;
        } else if (!message.session.shouldApply(message.decision)) {
            passed.incrementAndGet();
            outcome = OffloadOutcome.PASSED;
        } else if (!message.session.validate(message.snapshot, message.decision)) {
            rejectedStale.incrementAndGet();
            outcome = OffloadOutcome.REJECTED_STALE;
        } else {
            long start = System.nanoTime();
            try {
                message.session.apply(message.snapshot, message.decision);
                applyNanos.addAndGet(System.nanoTime() - start);
                applied.incrementAndGet();
                outcome = OffloadOutcome.APPLIED;
            } catch (Throwable t) {
                applyNanos.addAndGet(System.nanoTime() - start);
                failed.incrementAndGet();
                lastError.set(t);
                LOGGER.warn("interaction offload apply failed", t);
                outcome = OffloadOutcome.FAILED;
            }
        }
        lastOutcome.set(outcome);
        if (message.applied != null) {
            message.applied.complete(outcome);
        }
    }

    public Stats stats() {
        return new Stats(
                submitted.get(),
                emitted.get(),
                applied.get(),
                passed.get(),
                rejectedStale.get(),
                failed.get(),
                averageMicros(decideNanos.get(), submitted.get()),
                averageMicros(applyNanos.get(), applied.get()),
                lastOutcome.get(),
                lastEmitThread.get(),
                lastError.get() == null ? null : lastError.get().toString());
    }

    String lastEmitThread() {
        return lastEmitThread.get();
    }

    private static double averageMicros(long nanos, long count) {
        if (count <= 0L) {
            return 0.0;
        }
        return nanos / 1000.0 / count;
    }

    private static final class OffloadMessage<S, D> {
        private final S snapshot;
        private final OffloadSession<S, D> session;
        private final D decision;
        private final Throwable error;
        private final CompletableFuture<OffloadOutcome> applied;

        private OffloadMessage(S snapshot, OffloadSession<S, D> session, D decision,
                               Throwable error, CompletableFuture<OffloadOutcome> applied) {
            this.snapshot = snapshot;
            this.session = session;
            this.decision = decision;
            this.error = error;
            this.applied = applied;
        }
    }

    public record Stats(
            long submitted,
            long emitted,
            long applied,
            long passed,
            long rejectedStale,
            long failed,
            double avgDecideMicros,
            double avgApplyMicros,
            OffloadOutcome lastOutcome,
            String lastEmitThread,
            String lastError) {
    }
}
