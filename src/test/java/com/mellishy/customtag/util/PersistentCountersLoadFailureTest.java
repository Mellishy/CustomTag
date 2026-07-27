package com.mellishy.customtag.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the "unreadable ≠ missing" contract that protects every never-reuse-an-id file
 * (counters, request queue, player ids, freezes). Collapsing those two states used to silently
 * reset counters to zero and then overwrite the still-good file on disk.
 */
class PersistentCountersLoadFailureTest {

    @TempDir
    Path tempDir;

    @Test
    void unreadableFile_doesNotOverwriteTheRealHighWaterMark() throws Exception {
        Path file = tempDir.resolve("counters.json");
        // A path that exists but is a directory makes Files.readString throw - the same class of
        // failure as a permission error, without needing OS-specific chmod.
        Files.createDirectories(file);

        AtomicReference<Exception> reported = new AtomicReference<>();
        PersistentCounters counters = new PersistentCounters(file, Runnable::run, reported::set);

        assertNotNull(reported.get(), "load failure must be reported, not swallowed");
        long issued = counters.next("request");
        assertEquals(1, issued, "in-memory counter still works for this session");

        counters.flushNow();
        // the directory is still a directory - flushNow must have refused to write over it
        assertTrue(Files.isDirectory(file),
                "a failed load must never destroy the on-disk high-water mark by writing zeros over it");
    }

    @Test
    void corruptJson_doesNotOverwriteTheFile() throws Exception {
        Path file = tempDir.resolve("counters.json");
        String corrupt = "{not-valid-json";
        Files.writeString(file, corrupt);

        AtomicReference<Exception> reported = new AtomicReference<>();
        PersistentCounters counters = new PersistentCounters(file, Runnable::run, reported::set);
        assertNotNull(reported.get());
        counters.next("request");
        counters.flushNow();

        assertEquals(corrupt, Files.readString(file),
                "corrupt-but-present file must survive - staff can repair it, zeros cannot");
    }

    @Test
    void missingFile_startsFreshAndPersists() throws Exception {
        Path file = tempDir.resolve("counters.json");
        PersistentCounters counters = new PersistentCounters(file, Runnable::run, ex -> fail(ex));
        assertEquals(1, counters.next("request"));
        counters.flushNow();
        assertTrue(Files.exists(file));

        PersistentCounters restarted = new PersistentCounters(file, Runnable::run, ex -> fail(ex));
        assertEquals(2, restarted.next("request"));
    }
}
