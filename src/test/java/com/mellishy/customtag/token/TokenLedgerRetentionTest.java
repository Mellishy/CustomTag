package com.mellishy.customtag.token;

import com.mellishy.customtag.util.PersistentCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The monthly token ledger used to grow forever - audit had retention, the ledger did not.
 * Pins the sweep so a long-lived production server cannot fill the disk with tokens-YYYY-MM.jsonl.
 */
class TokenLedgerRetentionTest {

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong();
    private Path ledgerFolder;
    private TokenService tokens;

    @BeforeEach
    void setUp() throws Exception {
        ledgerFolder = tempDir.resolve("ledger");
        Files.createDirectories(ledgerFolder);
        PersistentCounters counters = new PersistentCounters(tempDir.resolve("counters.json"),
                Runnable::run, ex -> fail(ex));
        // fixed "now" so retention math is deterministic
        now.set(java.time.LocalDate.of(2026, 7, 15)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        tokens = new TokenService(ledgerFolder, tempDir.resolve("freezes.json"),
                counters, Runnable::run, ex -> fail(ex), now::get);
        tokens.setRetentionDays(90);
    }

    @Test
    void retentionSweep_deletesMonthsOlderThanTheWindow_keepsRecent() throws Exception {
        Path old = ledgerFolder.resolve("tokens-2025-01.jsonl");
        Path recent = ledgerFolder.resolve("tokens-2026-06.jsonl");
        Path current = ledgerFolder.resolve("tokens-2026-07.jsonl");
        Files.writeString(old, "{}\n");
        Files.writeString(recent, "{}\n");
        Files.writeString(current, "{}\n");

        tokens.runRetentionSweep();

        assertFalse(Files.exists(old), "Jan 2025 is more than 90 days before Jul 2026");
        assertTrue(Files.exists(recent), "June 2026 is inside the 90-day window");
        assertTrue(Files.exists(current));
    }

    @Test
    void retentionDisabled_keepsEverything() throws Exception {
        tokens.setRetentionDays(0);
        Path old = ledgerFolder.resolve("tokens-2020-01.jsonl");
        Files.writeString(old, "{}\n");

        tokens.runRetentionSweep();

        assertTrue(Files.exists(old));
    }
}
