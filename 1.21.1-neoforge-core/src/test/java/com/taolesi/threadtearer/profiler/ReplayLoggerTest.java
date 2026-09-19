package com.taolesi.threadtearer.profiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void exportWritesJsonEntries() throws Exception {
        ReplayLogger logger = new ReplayLogger();
        logger.configure(true, 1000);
        assertTrue(logger.startSession("test"));
        logger.recordTick(100, 20.0, 50.0, 1, 2, 0, 0, 1, true);
        logger.recordTick(101, 20.0, 49.0, 1, 0, 0, 0, 0, false);

        Path file = logger.export(tempDir);
        assertTrue(Files.exists(file), "export must create a file");
        String json = Files.readString(file);
        assertTrue(json.contains("\"scenario\": \"test\""));
        assertTrue(json.contains("\"tick\": 100"));
        assertTrue(json.contains("\"overrun\": true"));
    }

    @Test
    void recordBeforeSessionIsIgnored() throws Exception {
        ReplayLogger logger = new ReplayLogger();
        logger.configure(true, 100);
        logger.recordTick(1, 20.0, 50.0, 1, 0, 0, 0, 0, true);
        logger.startSession("s");

        Path file = logger.export(tempDir);
        String json = Files.readString(file);
        assertFalse(json.contains("\"tick\": 1"), "ticks before the session must not be exported");
    }
}
