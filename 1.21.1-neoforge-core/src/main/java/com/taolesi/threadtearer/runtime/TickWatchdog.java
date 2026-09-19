package com.taolesi.threadtearer.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the server thread from the OUTSIDE while a tick is stuck.
 *
 * <p>The first version of this diagnostic captured the stack from inside
 * {@code ServerTickEvent.Post}, which always reports the same frames (the
 * detector itself) and tells you nothing. A watchdog thread is the only way
 * to see where the server thread actually is when it is blocked.
 *
 * <p>On each stall it also dumps every {@code MCT-Compute} worker, so a
 * mutual block between the server thread and a compute worker is visible in
 * one sample.
 */
public final class TickWatchdog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("MCThread.Watchdog");

    /** How long without a completed tick before we call it a stall. */
    private static final long STALL_NANOS = 1_000_000_000L;
    /** How often the watchdog wakes up. */
    private static final long SAMPLE_INTERVAL_MS = 500L;
    /** Min gap between reports, so one long stall cannot burn the whole budget. */
    private static final long REPORT_INTERVAL_NANOS = 3_000_000_000L;
    /** Max stall reports per server session. */
    private static final int MAX_REPORTS = 40;

    private final AtomicLong lastTickNanos = new AtomicLong();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong reports = new AtomicLong();
    private final AtomicLong lastReportNanos = new AtomicLong();
    private volatile Thread serverThread;
    private volatile boolean running;
    private Thread thread;

    /** Called from the server thread at the end of every tick. */
    public void onTickEnd() {
        lastTickNanos.set(System.nanoTime());
        tickCount.incrementAndGet();
    }

    public void start(Thread serverThread) {
        this.serverThread = serverThread;
        this.lastTickNanos.set(System.nanoTime());
        this.running = true;
        this.thread = new Thread(this::loop, "MCT-Watchdog");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long last = lastTickNanos.get();
            long stuckNanos = System.nanoTime() - last;
            if (stuckNanos < STALL_NANOS) {
                continue;
            }
            if (reports.get() >= MAX_REPORTS) {
                continue;
            }
            // One long stall used to consume the whole budget at 500ms
            // intervals, hiding everything that happened later in the session.
            long now = System.nanoTime();
            long previous = lastReportNanos.get();
            if (now - previous < REPORT_INTERVAL_NANOS) {
                continue;
            }
            if (!lastReportNanos.compareAndSet(previous, now)) {
                continue;
            }
            reports.incrementAndGet();
            report(stuckNanos, last);
        }
    }

    private void report(long stuckNanos, long lastTick) {
        StringBuilder sb = new StringBuilder(String.format(
                "[Thread Tearer] server tick stuck for %d ms (tick=%d). Thread dumps:",
                stuckNanos / 1_000_000, tickCount.get()));

        Thread server = serverThread;
        if (server != null) {
            sb.append("\n  --- Server thread ---");
            for (StackTraceElement e : server.getStackTrace()) {
                sb.append("\n    at ").append(e);
            }
        }

        // Every compute worker: a worker parked on a lock the server thread
        // wants is the classic mutual block, and it shows up here directly.
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        int workers = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            String name = entry.getKey().getName();
            if (!name.startsWith("MCT-Compute") && !name.startsWith("MCT-Interaction")
                    && !name.startsWith("MCT-Tick") && !name.startsWith("MCT-Lifecycle")) {
                continue;
            }
            workers++;
            sb.append("\n  --- ").append(name).append(" ---");
            for (StackTraceElement e : entry.getValue()) {
                sb.append("\n    at ").append(e);
            }
        }
        sb.append("\n  (workers dumped: ").append(workers).append(')');

        LOGGER.warn(sb.toString());
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
