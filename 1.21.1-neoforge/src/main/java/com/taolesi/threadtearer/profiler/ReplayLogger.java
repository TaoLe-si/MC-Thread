package com.taolesi.threadtearer.profiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replay event stream (M1, v1).
 *
 * <p>Records one lightweight entry per server tick (TPS, tick duration,
 * online player count, overrun) while a session is open, and exports JSON. Full state-change
 * event sourcing (craft requests, storage events) is a later milestone.
 */
public final class ReplayLogger {

    public record ReplayEntry(int tick, double tps, double tickMs, int players,
                              int chunkLoads, int chunkUnloads,
                              int entityJoins, int entityLeaves,
                              boolean overrun) {
    }

    public record Export(String scenario, long sessionDurationMs, List<ReplayEntry> events) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<ReplayEntry> events = new ArrayDeque<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private volatile MinecraftServer server;
    private volatile boolean enabled = true;
    private volatile int maxEntries = 100_000;
    private volatile boolean sessionOpen;
    private volatile long sessionStartNanos;
    private volatile String scenario = "default";

    public void configure(boolean enabled, int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(100, maxEntries);
    }

    public void attach(MinecraftServer s) {
        this.server = s;
    }

    public void detach() {
        lock.lock();
        try {
            sessionOpen = false;
        } finally {
            lock.unlock();
        }
        this.server = null;
    }

    public boolean startSession(String scenario) {
        if (!enabled) {
            return false;
        }
        lock.lock();
        try {
            events.clear();
            this.scenario = scenario == null || scenario.isBlank() ? "default" : scenario;
            sessionStartNanos = System.nanoTime();
            sessionOpen = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void stopSession() {
        lock.lock();
        try {
            sessionOpen = false;
        } finally {
            lock.unlock();
        }
    }

    public boolean isSessionOpen() {
        return sessionOpen;
    }

    public void recordTick(int tick, double tps, double tickMs, int players,
                           int chunkLoads, int chunkUnloads,
                           int entityJoins, int entityLeaves,
                           boolean overrun) {
        lock.lock();
        try {
            if (!sessionOpen) {
                return;
            }
            if (events.size() >= maxEntries) {
                events.pollFirst();
            }
            events.addLast(new ReplayEntry(tick, tps, tickMs, players,
                    chunkLoads, chunkUnloads, entityJoins, entityLeaves, overrun));
        } finally {
            lock.unlock();
        }
    }

    public int entryCount() {
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }

    /** Exports the current session to {@code dir}/replay-{scenario}-{timestamp}.json. */
    public Path export(Path dir) throws IOException {
        lock.lock();
        try {
            Files.createDirectories(dir);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            Path file = dir.resolve("replay-" + scenario + "-" + stamp + ".json");
            long durationMs = (System.nanoTime() - sessionStartNanos) / 1_000_000L;
            List<ReplayEntry> snapshot = new ArrayList<>(events);
            Files.writeString(file, gson.toJson(new Export(scenario, durationMs, snapshot)));
            return file;
        } finally {
            lock.unlock();
        }
    }
}
