package com.taolesi.mcthread.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTest {

    @Test
    void holdsValueAndVersion() {
        SnapshotImpl<String> snapshot = new SnapshotImpl<>("abc", 7L);
        assertEquals("abc", snapshot.get());
        assertEquals(7L, snapshot.version());
        assertTrue(snapshot.isValid(7L));
        assertFalse(snapshot.isValid(8L));
    }
}
