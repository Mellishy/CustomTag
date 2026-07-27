package com.mellishy.customtag.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The security service is the first gate of every submission (rate limit, duplicate
 * swallowing, re-entrancy locks, maintenance freezes) - regressions here either let spam
 * through to staff/AI or lock legitimate players out, so all four gates are pinned down.
 */
class SecurityServiceTest {

    @TempDir
    Path tempDir;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private SecurityService security;

    @BeforeEach
    void setUp() {
        security = new SecurityService(tempDir.resolve("flags.json"), Runnable::run,
                ex -> fail(ex), now::get);
        // 3 submissions per 60s window, 10 minute duplicate window
        security.configure(3, 60_000L, 600_000L);
    }

    @Test
    void rateLimit_blocksAfterTheConfiguredBudget_andRecoversAfterTheWindow() {
        UUID player = UUID.randomUUID();
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "one"));
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "two"));
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "three"));
        assertEquals(SecurityService.Block.RATE_LIMITED, security.checkSubmission(player, "four"));
        // the block itself was recorded as a security flag
        assertTrue(security.flagsOf(player).getOrDefault("rate-limit", 0) >= 1);

        now.addAndGet(61_000);
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "five"));
    }

    @Test
    void duplicate_sameNormalizedTextWithinWindow_isSwallowed() {
        UUID player = UUID.randomUUID();
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "CoolTag"));
        // "C0ol-Tag" normalizes to the same canonical text - a classic resubmit-with-tricks case
        assertEquals(SecurityService.Block.DUPLICATE, security.checkSubmission(player, "C0ol-Tag"));
        // a different player submitting the same text is NOT a duplicate
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(UUID.randomUUID(), "CoolTag"));
    }

    @Test
    void maintenance_freezesSubmissionsEntirely_untilDisabled() {
        UUID player = UUID.randomUUID();
        assertTrue(security.enableMaintenance("submissions"));
        assertFalse(security.enableMaintenance("submissions"), "second enable reports already frozen");
        assertTrue(security.isUnderMaintenance("SUBMISSIONS"), "subsystem names are case-insensitive");

        assertEquals(SecurityService.Block.MAINTENANCE, security.checkSubmission(player, "tag"));

        assertTrue(security.disableMaintenance("submissions"));
        assertEquals(SecurityService.Block.NONE, security.checkSubmission(player, "tag"));
    }

    @Test
    void operationLock_isExclusivePerPlayer_andReleasable() {
        UUID player = UUID.randomUUID();
        assertTrue(security.tryAcquireOperationLock(player));
        assertFalse(security.tryAcquireOperationLock(player), "re-entry must be refused");
        assertTrue(security.tryAcquireOperationLock(UUID.randomUUID()), "other players are unaffected");

        security.releaseOperationLock(player);
        assertTrue(security.tryAcquireOperationLock(player));
    }

    @Test
    void flags_accumulatePerType_andSurviveRestart() {
        UUID player = UUID.randomUUID();
        security.flag(player, "blacklist-hit");
        security.flag(player, "blacklist-hit");
        security.flag(player, "unicode-abuse");

        assertEquals(2, security.flagsOf(player).get("blacklist-hit"));
        assertEquals(3, security.totalFlags(player));

        // restart: a fresh instance reading the same file sees the same flags
        SecurityService restarted = new SecurityService(tempDir.resolve("flags.json"),
                Runnable::run, ex -> fail(ex), now::get);
        assertEquals(3, restarted.totalFlags(player));
    }
}
