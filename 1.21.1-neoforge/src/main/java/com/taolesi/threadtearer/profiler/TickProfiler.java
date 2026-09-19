package com.taolesi.threadtearer.profiler;

import com.taolesi.threadtearer.profiler.ProfileReport.FrameStat;
import com.taolesi.threadtearer.profiler.ProfileReport.ModStat;
import net.minecraft.server.MinecraftServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tick attribution profiler (M1).
 *
 * <p>Two signal sources:
 * <ul>
 *   <li>sampling: a daemon thread samples the server thread stack every
 *       {@code intervalMs} and attributes frames to mods;</li>
 *   <li>tick timing: measures every server tick end-to-end (avg/max/overruns).</li>
 * </ul>
 */
public final class TickProfiler {

    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    private final ModResolver resolver = new ModResolver();

    private final Map<String, LongAdder> modHits = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> frameHits = new ConcurrentHashMap<>();
    private final LongAdder sampleCount = new LongAdder();

    private final LongAdder tickCount = new LongAdder();
    private final DoubleAdder tickMsTotal = new DoubleAdder();
    private final AtomicLong tickMsMax = new AtomicLong();
    private final LongAdder overrunTicks = new LongAdder();

    private volatile long lastTickMs;
    private volatile long lastTickEndNanos;
    private volatile boolean lastTickEndValid;

    private volatile MinecraftServer server;
    private volatile long serverThreadId = -1;

    private volatile boolean capturing;
    private volatile int sampleIntervalMs = 5;
    private volatile long captureStartNanos;
    private volatile long ticksAtCaptureStart;
    private volatile Thread sampler;

    public void attach(MinecraftServer s) {
        this.server = s;
        Thread thread = s.getRunningThread();
        this.serverThreadId = thread != null ? thread.getId() : -1;
    }

    public void detach() {
        stopCapture();
        this.server = null;
        this.serverThreadId = -1;
        this.lastTickEndValid = false;
    }

    /** Called at every server tick END. */
    public void onTickEnd() {
        long now = System.nanoTime();
        if (lastTickEndValid) {
            double ms = (now - lastTickEndNanos) / 1_000_000.0;
            lastTickMs = (long) ms;
            tickCount.increment();
            tickMsTotal.add(ms);
            tickMsMax.accumulateAndGet((long) ms, Math::max);
            if (ms > 50.0) {
                overrunTicks.increment();
            }
        }
        lastTickEndNanos = now;
        lastTickEndValid = true;
    }

    public boolean startCapture(int intervalMs) {
        if (capturing || serverThreadId < 0) {
            return false;
        }
        this.sampleIntervalMs = Math.max(1, intervalMs);
        modHits.clear();
        frameHits.clear();
        sampleCount.reset();
        ticksAtCaptureStart = tickCount.sum();
        captureStartNanos = System.nanoTime();
        capturing = true;
        sampler = new Thread(this::sampleLoop, "MCT-Profiler-Sampler");
        sampler.setDaemon(true);
        sampler.start();
        return true;
    }

    public void stopCapture() {
        capturing = false;
        Thread s = sampler;
        if (s != null) {
            try {
                s.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sampler = null;
        }
    }

    private void sampleLoop() {
        while (capturing) {
            long id = serverThreadId;
            if (id >= 0) {
                try {
                    ThreadInfo info = threadMx.getThreadInfo(id, 48);
                    if (info != null && info.getStackTrace() != null) {
                        sampleCount.increment();
                        // Self-time attribution: each sample counts exactly one frame -
                        // the deepest non-noise frame (the method actually executing).
                        StackTraceElement self = null;
                        for (StackTraceElement frame : info.getStackTrace()) {
                            String cls = frame.getClassName();
                            if (isNoise(cls)) {
                                continue;
                            }
                            self = frame;
                            break;
                        }
                        if (self != null) {
                            String cls = self.getClassName();
                            modHits.computeIfAbsent(resolver.modOf(cls), k -> new LongAdder()).increment();
                            frameHits.computeIfAbsent(cls + "." + self.getMethodName(), k -> new LongAdder()).increment();
                        }
                    }
                } catch (Throwable ignored) {
                    // sampling must never disturb the server
                }
            }
            try {
                Thread.sleep(sampleIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean isNoise(String cls) {
        return cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.");
    }

    public ProfileReport report(String scenario) {
        long totalSamples = sampleCount.sum();
        long ticks = tickCount.sum();
        double avgTickMs = ticks == 0 ? 0 : tickMsTotal.sum() / ticks;
        List<ModStat> mods = modHits.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed())
                .map(e -> {
                    double pct = percent(e.getValue().sum(), totalSamples);
                    return new ModStat(e.getKey(), e.getValue().sum(), pct, avgTickMs * pct / 100.0);
                })
                .toList();
        List<FrameStat> hotspots = frameHits.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed())
                .limit(25)
                .map(e -> new FrameStat(e.getKey(), e.getValue().sum()))
                .toList();
        return new ProfileReport(
                scenario,
                totalSamples,
                (System.nanoTime() - captureStartNanos) / 1_000_000L,
                mods,
                hotspots,
                avgTickMs,
                tickMsMax.get(),
                ticks,
                overrunTicks.sum(),
                gcTimeMs());
    }

    public long ticksSinceCaptureStart() {
        return tickCount.sum() - ticksAtCaptureStart;
    }

    public long lastTickMs() {
        return lastTickMs;
    }

    public boolean isCapturing() {
        return capturing;
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0 : part * 100.0 / total;
    }

    private static long gcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gc.getCollectionTime();
        }
        return total;
    }
}
