package com.taolesi.mcthread.profiler;

import java.util.List;

/** Gson-friendly profile report produced by {@link TickProfiler}. */
public final class ProfileReport {

    public final String scenario;
    public final long sampleCount;
    public final long captureDurationMs;
    public final List<ModStat> mods;
    public final List<FrameStat> hotspots;
    public final double tickAvgMs;
    public final long tickMaxMs;
    public final long tickCount;
    public final long overrunTicks;
    public final long gcTimeMs;

    public ProfileReport(String scenario, long sampleCount, long captureDurationMs,
                         List<ModStat> mods, List<FrameStat> hotspots,
                         double tickAvgMs, long tickMaxMs, long tickCount,
                         long overrunTicks, long gcTimeMs) {
        this.scenario = scenario;
        this.sampleCount = sampleCount;
        this.captureDurationMs = captureDurationMs;
        this.mods = mods;
        this.hotspots = hotspots;
        this.tickAvgMs = tickAvgMs;
        this.tickMaxMs = tickMaxMs;
        this.tickCount = tickCount;
        this.overrunTicks = overrunTicks;
        this.gcTimeMs = gcTimeMs;
    }

    public static final class ModStat {
        public final String modId;
        public final long samples;
        public final double pct;
        /** Estimated server-thread time per tick contributed by this mod (sampling estimate). */
        public final double estTickMs;

        public ModStat(String modId, long samples, double pct, double estTickMs) {
            this.modId = modId;
            this.samples = samples;
            this.pct = pct;
            this.estTickMs = estTickMs;
        }
    }

    public static final class FrameStat {
        public final String frame;
        public final long samples;

        public FrameStat(String frame, long samples) {
            this.frame = frame;
            this.samples = samples;
        }
    }
}
