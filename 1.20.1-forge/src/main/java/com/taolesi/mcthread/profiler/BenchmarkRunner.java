package com.taolesi.mcthread.profiler;

/**
 * Benchmark runner (M1): runs the sampling profiler for a fixed tick window
 * and produces a comparable report. Every future optimization must be gated
 * by an A/B comparison of these reports (Rule-008 / ADR-009).
 */
public final class BenchmarkRunner {

    private final TickProfiler profiler;
    private final ReplayLogger replay;

    private volatile boolean running;
    private volatile int targetTicks;
    private volatile String scenario = "default";
    private volatile ProfileReport lastReport;

    public BenchmarkRunner(TickProfiler profiler, ReplayLogger replay) {
        this.profiler = profiler;
        this.replay = replay;
    }

    public boolean start(String scenario, int ticks, int intervalMs) {
        if (running) {
            return false;
        }
        this.scenario = scenario == null || scenario.isBlank() ? "default" : scenario;
        this.targetTicks = Math.max(10, ticks);
        if (!profiler.startCapture(intervalMs)) {
            return false;
        }
        replay.startSession(this.scenario);
        running = true;
        return true;
    }

    /** Called every server tick; auto-stops when the target window is reached. */
    public void onTick() {
        if (!running) {
            return;
        }
        if (profiler.ticksSinceCaptureStart() >= targetTicks) {
            stop();
        }
    }

    public ProfileReport stop() {
        if (!running) {
            return lastReport;
        }
        running = false;
        profiler.stopCapture();
        replay.stopSession();
        lastReport = profiler.report(scenario);
        return lastReport;
    }

    public boolean isRunning() {
        return running;
    }

    public ProfileReport lastReport() {
        return lastReport;
    }
}
